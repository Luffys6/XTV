package com.family.quizlock;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 局域网管理面板（电视自己就是服务器，不需要任何云端/服务器）。
 *
 *  - 监听 0.0.0.0:8099，手机连同一 WiFi 后浏览器打开 http://<电视IP>:8099
 *  - 页面来自 assets/admin/admin.html，纯前端 fetch 调下面的接口
 *  - 鉴权：POST /api/auth 用家长密码换 token（12 小时），连错 5 次锁 1 分钟
 *  - 接口：
 *      GET  /api/hello                     设备信息（免鉴权）
 *      GET  /api/state                     规则 + 统计
 *      POST /api/config                    修改规则 {intervalMinutes,requiredCorrect,grade,semester,difficulty,enabled}
 *      POST /api/service                   启动/停止守护服务 {running:true|false}
 *      GET  /api/questions                 题库（内置/自建/AI 缓存）
 *      POST /api/questions                 批量导入自建题 {"items":[...]}，整批校验
 *      DELETE /api/questions/{id}          删除自建题
 *      GET  /api/wrongbook                 错题本
 *      POST /api/password                  修改家长密码 {old,new}
 */
public final class HttpAdminServer {

    private static final String TAG = "HttpAdmin";
    public static final int PORT = 8099;
    private static final long TOKEN_TTL_MS = 12L * 60 * 60 * 1000;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static Context app;
    private static ServerSocket serverSocket;
    private static Thread acceptThread;
    private static volatile boolean running = false;

    /** token -> 过期时间戳 */
    private static final Map<String, Long> tokens = new HashMap<String, Long>();
    private static int failCount = 0;
    private static long lockUntil = 0;

    private HttpAdminServer() {
    }

    public static boolean isRunning() {
        return running;
    }

    /** 面板地址（取局域网 IPv4），取不到时返回 127.0.0.1 */
    public static String url() {
        String ip = localIp();
        return "http://" + (ip == null ? "127.0.0.1" : ip) + ":" + PORT;
    }

    public static synchronized void start(Context c) {
        if (running) return;
        app = c.getApplicationContext();
        running = true;
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("0.0.0.0", PORT));
        } catch (Exception e) {
            running = false;
            Log.e(TAG, "管理面板启动失败（端口 " + PORT + "）：" + e.getMessage());
            return;
        }
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "管理面板已启动：" + url());
                while (running) {
                    Socket s = null;
                    try {
                        s = serverSocket.accept();
                        handle(s);
                    } catch (Exception e) {
                        if (running) Log.w(TAG, "请求处理异常：" + e.getMessage());
                    } finally {
                        closeQuietly(s);
                    }
                }
            }
        }, "admin-http");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public static synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {
        }
        serverSocket = null;
        acceptThread = null;
    }

    // ---------------------------------------------------------------- 请求处理

    private static void handle(Socket socket) throws Exception {
        socket.setSoTimeout(15000);
        InputStream in = socket.getInputStream();

        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isEmpty()) return;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return;
        String method = parts[0].toUpperCase(Locale.US);
        String target = parts[1];

        Map<String, String> headers = new HashMap<String, String>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int idx = line.indexOf(':');
            if (idx > 0) {
                headers.put(line.substring(0, idx).trim().toLowerCase(Locale.US),
                        line.substring(idx + 1).trim());
            }
        }

        int contentLength = 0;
        try {
            String cl = headers.get("content-length");
            if (cl != null) contentLength = Integer.parseInt(cl);
        } catch (Exception ignored) {
        }
        byte[] bodyBytes = new byte[contentLength];
        int read = 0;
        while (read < contentLength) {
            int n = in.read(bodyBytes, read, contentLength - read);
            if (n < 0) break;
            read += n;
        }
        String body = new String(bodyBytes, 0, read, UTF8);

        String path = target;
        String query = "";
        int qIdx = target.indexOf('?');
        if (qIdx >= 0) {
            path = target.substring(0, qIdx);
            query = target.substring(qIdx + 1);
        }

        Response resp;
        try {
            resp = route(method, path, query, headers, body);
        } catch (Exception e) {
            resp = json(500, err("服务器内部错误：" + e.getMessage()));
        }
        writeResponse(socket.getOutputStream(), resp);
    }

    private static Response route(String method, String path, String query,
                                  Map<String, String> headers, String body) {
        // 首页：管理面板 HTML
        if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
            return htmlPage();
        }
        if ("GET".equals(method) && "/favicon.ico".equals(path)) {
            return new Response(204, "text/plain", new byte[0]);
        }
        if ("GET".equals(method) && "/api/hello".equals(path)) {
            return json(200, hello());
        }
        if ("POST".equals(method) && "/api/auth".equals(path)) {
            return auth(body);
        }

        // 以下接口都需要 token
        if (path.startsWith("/api/")) {
            if (!checkToken(headers, query)) {
                return json(401, err("未登录或登录已过期，请重新输入密码"));
            }
            if ("GET".equals(method) && "/api/state".equals(path)) return state();
            if ("POST".equals(method) && "/api/config".equals(path)) return saveConfig(body);
            if ("POST".equals(method) && "/api/license".equals(path)) return activateLicense(body);
            if ("POST".equals(method) && "/api/service".equals(path)) return serviceControl(body);
            if ("GET".equals(method) && "/api/questions".equals(path)) return questions(query);
            if ("POST".equals(method) && "/api/questions".equals(path)) return importQuestions(body);
            if ("DELETE".equals(method) && path.startsWith("/api/questions/")) {
                return deleteQuestion(path.substring("/api/questions/".length()));
            }
            if ("GET".equals(method) && "/api/wrongbook".equals(path)) return wrongbook();
            if ("DELETE".equals(method) && path.startsWith("/api/wrongbook/")) {
                return deleteWrong(path.substring("/api/wrongbook/".length()));
            }
            if ("POST".equals(method) && "/api/wrongbook/clear".equals(path)) return clearWrong();
            if ("POST".equals(method) && "/api/password".equals(path)) return changePassword(body);
        }
        return json(404, err("接口不存在"));
    }

    // ---------------------------------------------------------------- 接口实现

    private static JSONObject hello() {
        JSONObject o = new JSONObject();
        try {
            o.put("name", "学习小卫士 · 家长管理");
            o.put("version", "1.3");
            o.put("port", PORT);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static Response auth(String body) {
        long now = System.currentTimeMillis();
        if (now < lockUntil) {
            return json(429, err("密码错误次数过多，请 " + ((lockUntil - now) / 1000 + 1) + " 秒后再试"));
        }
        String pwd = optString(body, "password", "");
        String real = Store.password(app);
        if (real.isEmpty()) {
            return json(400, err("设备尚未设置家长密码，请先在电视上打开 App 完成设置"));
        }
        if (!pwd.equals(real)) {
            failCount++;
            if (failCount >= 5) {
                lockUntil = now + 60000;
                failCount = 0;
            }
            return json(401, err("密码错误"));
        }
        failCount = 0;
        String token = UUID.randomUUID().toString().replace("-", "");
        synchronized (tokens) {
            tokens.put(token, now + TOKEN_TTL_MS);
        }
        JSONObject o = new JSONObject();
        try {
            o.put("token", token);
            o.put("expiresIn", TOKEN_TTL_MS / 1000);
        } catch (Exception ignored) {
        }
        return json(200, o);
    }

    private static JSONObject licenseJson() throws Exception {
        JSONObject l = new JSONObject();
        l.put("deviceCode", License.deviceCode(app));
        l.put("enforced", License.ENFORCED);
        l.put("activated", License.isActivated(app));
        l.put("permanent", License.isPermanent(app));
        l.put("trial", License.isTrial(app));
        l.put("trialDays", License.TRIAL_DAYS);
        l.put("expireDate", License.expireText(app));
        l.put("daysLeft", License.daysLeft(app));
        l.put("expired", License.isExpired(app));
        l.put("tampered", License.isTampered(app));
        l.put("usable", License.isUsable(app));
        l.put("summary", License.summary(app));
        return l;
    }

    /** 手机面板续期：POST /api/license {code:"123456789012"} */
    private static Response activateLicense(String body) {
        if (!License.ENFORCED) {
            return json(400, err("当前版本未启用使用期限限制（永久使用），无需激活"));
        }
        String code = optString(body, "code", "");
        if (!License.activate(app, code)) {
            return json(400, err("激活码无效（需 12 位数字，且与这台电视的设备码匹配）"));
        }
        License.touch(app);
        return state();
    }

    private static Response state() {
        JSONObject o = new JSONObject();
        try {
            QuestionBank.ensure(app);   // 面板可能在没弹过题时就打开，先把当前年级题库准备好
            o.put("enabled", Store.enabled(app));
            o.put("intervalMinutes", Store.intervalMinutes(app));
            o.put("requiredCorrect", Store.requiredCorrect(app));
            o.put("grade", Store.grade(app));
            o.put("semester", Store.semester(app));
            o.put("semesterName", Store.semesterName(Store.semester(app)));
            o.put("difficulty", Store.difficulty(app));
            o.put("difficultyName", Store.difficultyName(Store.difficulty(app)));
            o.put("watchedSeconds", Store.watchedSeconds(app));
            o.put("todayWrong", Store.wrongToday(app));
            o.put("wrongCount", Store.wrongBook(app).size());
            o.put("statTotal", Store.statTotal(app));
            o.put("statCorrect", Store.statCorrect(app));
            o.put("builtin", QuestionBank.builtinCount());
            o.put("custom", QuestionBank.customCount());
            o.put("aiCache", QuestionBank.aiCount());
            o.put("aiConfigured", AiQuestionClient.isConfigured(app));
            o.put("passwordSet", Store.hasPassword(app));
            o.put("serviceRunning", WatchMonitorService.running);
            o.put("license", licenseJson());
        } catch (Exception ignored) {
        }
        return json(200, o);
    }

    private static Response saveConfig(String body) {
        try {
            JSONObject o = new JSONObject(body);
            if (o.has("intervalMinutes")) Store.setIntervalMinutes(app, o.getInt("intervalMinutes"));
            if (o.has("requiredCorrect")) Store.setRequiredCorrect(app, o.getInt("requiredCorrect"));
            if (o.has("grade")) {
                int oldGrade = Store.grade(app);
                Store.setGrade(app, o.getInt("grade"));
                if (Store.grade(app) != oldGrade) {
                    QuestionBank.onGradeChanged(app);
                    // 换了年级要按新年级补题；走防抖（5 秒），手机面板上连点切换
                    // 不会连环发请求，停手后按最终年级只补一批
                    AiQuestionClient.prefetchDebounced(app);
                }
            }
            if (o.has("difficulty")) {
                int oldDiff = Store.difficulty(app);
                Store.setDifficulty(app, o.getInt("difficulty"));
                if (Store.difficulty(app) != oldDiff) {
                    QuestionBank.onDifficultyChanged(app);
                    AiQuestionClient.prefetchDebounced(app);
                }
            }
            if (o.has("semester")) {
                int oldSem = Store.semester(app);
                Store.setSemester(app, o.getInt("semester"));
                if (Store.semester(app) != oldSem) {
                    QuestionBank.onSemesterChanged(app);
                    AiQuestionClient.prefetchDebounced(app);
                }
            }
            if (o.has("enabled")) Store.setEnabled(app, o.getBoolean("enabled"));
            // 手机上把守护打开时，若服务还没跑就顺手拉起，否则"开了也不计时、不弹题"
            if (Store.enabled(app) && !WatchMonitorService.running) {
                WatchMonitorService.start(app);
            }
            return state();
        } catch (Exception e) {
            return json(400, err("参数格式错误：" + e.getMessage()));
        }
    }

    /** 手机端启动 / 停止守护服务 {running: true|false} */
    private static Response serviceControl(String body) {
        try {
            JSONObject o = new JSONObject(body);
            boolean run = o.optBoolean("running", WatchMonitorService.running);
            if (run && !WatchMonitorService.running) {
                WatchMonitorService.start(app);
            } else if (!run && WatchMonitorService.running) {
                WatchMonitorService.stop(app);
            }
            // 状态写入后稍等片刻再读，让 running 标志先更新
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {
            }
            return state();
        } catch (Exception e) {
            return json(400, err("参数格式错误：" + e.getMessage()));
        }
    }

    private static Response questions(String query) {
        QuestionBank.ensure(app);
        int limit = 2000;
        String sourceFilter = null;
        try {
            String[] pairs = query.split("&");
            for (String p : pairs) {
                if (p.startsWith("limit=")) limit = Integer.parseInt(p.substring(6));
                if (p.startsWith("source=")) sourceFilter = p.substring(7);
            }
        } catch (Exception ignored) {
        }
        JSONArray items = new JSONArray();
        int count = 0;
        try {
            for (Question q : QuestionBank.snapshot()) {
                if (count >= limit) break;
                String src = QuestionBank.isCustom(q.id) ? "custom"
                        : (q.id.startsWith("ai_") || q.id.startsWith("cache_")) ? "ai" : "builtin";
                // source=custom：手机面板只拉自建题，避免整库刷屏
                if (sourceFilter != null && !sourceFilter.equals(src)) continue;
                JSONObject o = new JSONObject();
                o.put("id", q.id);
                o.put("subject", q.subject);
                o.put("subjectName", QuestionBank.subjectName(q.subject));
                o.put("q", q.q);
                JSONArray opts = new JSONArray();
                for (String s : q.options) opts.put(s);
                o.put("options", opts);
                o.put("answer", q.answer);
                o.put("explain", q.explain);
                o.put("source", src);
                // 真实出处：哪个平台、哪个模型出的（本地题为空），手机面板可直接展示
                o.put("provider", q.source);
                o.put("model", q.model);
                items.put(o);
                count++;
            }
            JSONObject res = new JSONObject();
            res.put("items", items);
            res.put("total", items.length());
            return json(200, res);
        } catch (Exception e) {
            return json(500, err("读取题库失败：" + e.getMessage()));
        }
    }

    /** 批量导入自建题：整批校验，任何一道不合法就整批拒绝（不做部分导入） */
    private static Response importQuestions(String body) {
        JSONArray items;
        try {
            JSONObject root = new JSONObject(body);
            items = root.getJSONArray("items");
        } catch (Exception e) {
            return json(400, err("请求体需为 {\"items\":[...]}"));
        }
        if (items.length() == 0) return json(400, err("items 为空"));
        if (items.length() > 50) return json(400, err("单次最多导入 50 题"));

        List<Question> parsed = new ArrayList<Question>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject o;
            try {
                o = items.getJSONObject(i);
            } catch (Exception e) {
                return json(400, err("第 " + (i + 1) + " 题不是合法对象"));
            }
            String q = optStringObj(o, "q", "").trim();
            if (q.isEmpty()) return json(400, err("第 " + (i + 1) + " 题缺少题干 q"));
            String subject = normalizeSubject(optStringObj(o, "subject", "math"));
            JSONArray opts = o.optJSONArray("options");
            if (opts == null) opts = o.optJSONArray("a");
            if (opts == null || opts.length() < 2 || opts.length() > 8) {
                return json(400, err("第 " + (i + 1) + " 题的选项需为 2~8 个（字段 options）"));
            }
            List<String> ol = new ArrayList<String>();
            for (int j = 0; j < opts.length(); j++) {
                String t = String.valueOf(opts.opt(j)).trim();
                if (t.isEmpty() || "null".equals(t)) {
                    return json(400, err("第 " + (i + 1) + " 题第 " + (j + 1) + " 个选项为空"));
                }
                ol.add(t);
            }
            int answer = o.has("answer") ? o.optInt("answer", -1) : o.optInt("k", -1);
            if (answer < 0 || answer >= ol.size()) {
                return json(400, err("第 " + (i + 1) + " 题的 answer 下标越界（0~" + (ol.size() - 1) + "）"));
            }
            String explain = optStringObj(o, "explain", "");
            parsed.add(new Question("c_" + QuestionBank.stableId(subject, q),
                    subject, q, ol, answer, explain));
        }

        int added = QuestionBank.addCustom(app, parsed);
        JSONObject res = new JSONObject();
        try {
            res.put("ok", true);
            res.put("added", added);
            res.put("updated", parsed.size() - added);
            res.put("customTotal", QuestionBank.customCount());
        } catch (Exception ignored) {
        }
        return json(200, res);
    }

    private static Response deleteQuestion(String id) {
        if (!QuestionBank.isCustom(id)) {
            return json(400, err("只能删除家长自建题（内置题和 AI 题不可删）"));
        }
        boolean ok = QuestionBank.removeCustom(app, id);
        if (!ok) return json(404, err("题目不存在"));
        JSONObject res = new JSONObject();
        try {
            res.put("ok", true);
            res.put("customTotal", QuestionBank.customCount());
        } catch (Exception ignored) {
        }
        return json(200, res);
    }

    private static Response wrongbook() {
        Map<String, Integer> wrong = Store.wrongBook(app);
        List<Map.Entry<String, Integer>> list =
                new ArrayList<Map.Entry<String, Integer>>(wrong.entrySet());
        Collections.sort(list, new java.util.Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue() - a.getValue();
            }
        });
        JSONArray items = new JSONArray();
        int missing = 0;
        try {
            for (Map.Entry<String, Integer> e : list) {
                Question q = QuestionBank.findById(app, e.getKey());
                if (q == null) {
                    // 题目已经不在题库里了（被 30 题上限淘汰 / 那个年级的题库被清理）：
                    // 答错当时存过一份快照，照样把题干和答案摆出来，不让家长看到空白条目
                    JSONObject d = Store.wrongDetail(app, e.getKey());
                    if (d == null) {
                        missing++;
                        continue;
                    }
                    JSONObject o = new JSONObject();
                    o.put("id", e.getKey());
                    o.put("subject", QuestionBank.subjectName(d.optString("subject", "")));
                    o.put("q", d.optString("q", ""));
                    o.put("answer", d.optString("answer", ""));
                    o.put("explain", d.optString("explain", ""));
                    o.put("times", e.getValue());
                    o.put("outOfBank", true);
                    items.put(o);
                    continue;
                }
                JSONObject o = new JSONObject();
                o.put("id", q.id);
                o.put("subject", QuestionBank.subjectName(q.subject));
                o.put("q", q.q);
                o.put("answer", q.options.get(q.answer));
                o.put("explain", q.explain);
                o.put("times", e.getValue());
                items.put(o);
            }
            JSONObject res = new JSONObject();
            res.put("items", items);
            res.put("missing", missing);
            res.put("todayWrong", Store.wrongToday(app));
            return json(200, res);
        } catch (Exception e) {
            return json(500, err("读取错题本失败"));
        }
    }

    /** 从错题本移除单题（家长判断孩子已掌握） */
    private static Response deleteWrong(String id) {
        if (id == null || id.isEmpty()) return json(400, err("缺少题目 id"));
        if (!Store.removeWrong(app, id)) return json(404, err("错题本里没有这道题"));
        JSONObject res = new JSONObject();
        try {
            res.put("ok", true);
            res.put("wrongCount", Store.wrongBook(app).size());
        } catch (Exception ignored) {
        }
        return json(200, res);
    }

    /** 清空错题本 */
    private static Response clearWrong() {
        Store.clearWrongBook(app);
        JSONObject res = new JSONObject();
        try {
            res.put("ok", true);
            res.put("wrongCount", 0);
        } catch (Exception ignored) {
        }
        return json(200, res);
    }

    private static Response changePassword(String body) {
        String oldPwd = optString(body, "old", "");
        String newPwd = optString(body, "new", "");
        if (!oldPwd.equals(Store.password(app))) return json(401, err("原密码错误"));
        if (newPwd.length() < 6 || newPwd.length() > 8 || !newPwd.matches("\\d+")) {
            return json(400, err("新密码需为 6~8 位数字"));
        }
        Store.setPassword(app, newPwd);
        synchronized (tokens) {
            tokens.clear();
        }
        return json(200, err("密码已修改，请重新登录"));
    }

    // ---------------------------------------------------------------- 鉴权

    private static boolean checkToken(Map<String, String> headers, String query) {
        String token = null;
        String auth = headers.get("authorization");
        if (auth != null && auth.toLowerCase(Locale.US).startsWith("bearer ")) {
            token = auth.substring(7).trim();
        }
        if (token == null && query != null) {
            for (String p : query.split("&")) {
                if (p.startsWith("token=")) token = p.substring(6);
            }
        }
        if (token == null || token.isEmpty()) return false;
        Long expire = tokens.get(token);
        if (expire == null) return false;
        if (System.currentTimeMillis() > expire) {
            synchronized (tokens) {
                tokens.remove(token);
            }
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- HTTP 底层

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') buf.write(c);
            if (buf.size() > 8192) break;
        }
        if (c < 0 && buf.size() == 0) return null;
        return new String(buf.toByteArray(), UTF8);
    }

    private static void writeResponse(OutputStream out, Response r) throws Exception {
        byte[] body = r.body == null ? new byte[0] : r.body;
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(r.code).append(' ').append(statusText(r.code)).append("\r\n");
        head.append("Content-Type: ").append(r.contentType).append("\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Cache-Control: no-store\r\n");
        head.append("Access-Control-Allow-Origin: *\r\n");
        head.append("Access-Control-Allow-Headers: Authorization, Content-Type\r\n");
        head.append("Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n");
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(UTF8));
        out.write(body);
        out.flush();
    }

    private static String statusText(int code) {
        if (code == 200) return "OK";
        if (code == 204) return "No Content";
        if (code == 400) return "Bad Request";
        if (code == 401) return "Unauthorized";
        if (code == 404) return "Not Found";
        if (code == 429) return "Too Many Requests";
        return "Internal Server Error";
    }

    private static Response json(int code, JSONObject o) {
        return new Response(code, "application/json; charset=utf-8", o.toString().getBytes(UTF8));
    }

    private static JSONObject err(String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("message", message);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static Response htmlPage() {
        try {
            InputStream is = app.getAssets().open("admin/admin.html");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            return new Response(200, "text/html; charset=utf-8", bos.toByteArray());
        } catch (Exception e) {
            return new Response(500, "text/plain; charset=utf-8",
                    ("管理页面缺失：" + e.getMessage()).getBytes(UTF8));
        }
    }

    private static String optString(String json, String key, String def) {
        try {
            JSONObject o = new JSONObject(json);
            return o.optString(key, def);
        } catch (Exception e) {
            return def;
        }
    }

    private static String optStringObj(JSONObject o, String key, String def) {
        String v = o.optString(key, def);
        return v == null ? def : v;
    }

    private static String normalizeSubject(String s) {
        if (s == null) return "math";
        String v = s.trim().toLowerCase(Locale.US);
        if ("math".equals(v) || "数学".equals(s)) return "math";
        if ("chinese".equals(v) || "语文".equals(s)) return "chinese";
        if ("english".equals(v) || "英语".equals(s)) return "english";
        return "math";
    }

    private static String localIp() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                String name = nif.getName() == null ? "" : nif.getName().toLowerCase(Locale.US);
                if (name.startsWith("p2p") || name.startsWith("dummy")) continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        if (ip != null && ip.startsWith("192.168.")) return ip;
                    }
                }
            }
            // 没有 192.168 网段就退而取任意非回环 IPv4
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void closeQuietly(Socket s) {
        try {
            if (s != null) s.close();
        } catch (Exception ignored) {
        }
    }

    private static final class Response {
        final int code;
        final String contentType;
        final byte[] body;

        Response(int code, String contentType, byte[] body) {
            this.code = code;
            this.contentType = contentType;
            this.body = body;
        }
    }
}
