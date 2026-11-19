package com.family.quizlock;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 出题客户端（OpenAI 兼容接口；主平台智谱，可选备用平台如硅基流动）。
 *
 * 工作方式：
 *  - 后台线程预生成一批题（按当前年级 + 随机科目），直接写进该年级的本地题库
 *  - 弹题时从题库抽题；AI 不可用/未配置时用内置题兜底，年级不会串
 *  - 主平台候选模型全挂后，若配了备用平台 Key（ai_key2），自动切换到备用平台
 *  - 换 key / 换平台只需改 base_url + model，无需改代码
 *
 * TV 上配置（遥控器打字不便，直接 adb）：
 *  adb shell am force-stop com.family.quizlock
 *  adb shell am start -n com.family.quizlock/.MainActivity \
 *      --es api_key "你的Key" \
 *      --es ai_model "glm-4-flash" \
 *      --es ai_base_url "https://open.bigmodel.cn/api/paas/v4/chat/completions" \
 *      --es api_key2 "硅基流动的Key" \
 *      --es ai_model2 "Qwen/Qwen3-8B"
 */
public final class AiQuestionClient {
    private static final String TAG = "AiQuestionClient";
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final Random RND = new Random();
    private static final String[] SUBJECTS = {"math", "chinese", "english"};

    /**
     * 正在执行/已排队的出题任务对应的"年级_难度"。
     * 用于两点：① 连点年级按钮时不重复排一串长请求；
     * ② 队列里的旧任务开工时发现年级已变，立刻退出（不白等 1 分钟，也不串年级）。
     */
    private static volatile String pendingKey = "";

    /**
     * 每日缓存任务是否已排队/在跑。必须在"排队前"就占位：
     * 否则 onCreate 先调 dailyRefresh、紧接着调 prefetch，prefetch 在主线程就把 pendingKey 占了，
     * 排队中的每日任务一开工就判成"已有任务在跑"直接退出，日期标记永远打不上、每天白排一趟。
     */
    private static volatile boolean dailyPending;

    /** 切年级/切难度后的防抖：停手 5 秒没有再切，才真正发起出题请求 */
    private static final long DEBOUNCE_MS = 5000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Runnable pendingDebounce;

    private AiQuestionClient() {
    }

    /** 年级+学期+难度的快照键，出题全流程都靠它对齐 */
    private static String gradeDiffKey(int grade, int semester, int diff) {
        return "g" + grade + "_s" + semester + "_d" + diff;
    }

    public static boolean isConfigured(Context c) {
        // 只配了备用平台 Key 也算接入：主平台候选为空时，providers() 会直接打备用平台
        return !Store.aiKey(c).isEmpty() || !Store.aiKey2(c).isEmpty();
    }

    /**
     * 平台对外显示名：主平台 = AI1、备用平台 = AI2，界面上一律不出现厂商名和模型名
     * （"智谱 · glm-5.3-flash"这类只留在 logcat / 管理面板接口里，方便家长排查）。
     *
     * @param provider 题目里记的来源平台（hostTag 的结果，备用平台可能带 #2 后缀）
     */
    public static String alias(Context c, String provider) {
        String tag = provider == null ? "" : provider.trim();
        if (tag.isEmpty()) return "AI";
        // 顺序和降级链一致：主平台在前 → AI1，备用平台 → AI2
        if (!Store.aiKey(c).isEmpty() && tag.equals(hostTag(Store.aiBaseUrl(c)))) return "AI1";
        String bak = hostTag(Store.aiBaseUrl2(c));
        if (tag.equals(bak) || tag.equals(bak + "#2")) return "AI2";
        // 老数据 / 平台配置改过的题：按已知平台固定编号兜底，仍然不露厂商名
        if (tag.contains("智谱") || tag.contains("bigmodel")) return "AI1";
        if (tag.contains("硅基") || tag.contains("siliconflow")) return "AI2";
        return "AI";
    }

    /**
     * 防抖版预取：年级/难度按钮连点时不急着发请求。
     * 每次切换都重置 5 秒倒计时，停手后按"最终停留的年级+难度"只发一批，
     * 避免来回切产生一串出题请求，也避免中途状态生成的题白跑一趟。
     */
    public static void prefetchDebounced(final Context c) {
        if (!isConfigured(c)) return;
        final Context app = c.getApplicationContext();
        if (pendingDebounce != null) {
            MAIN.removeCallbacks(pendingDebounce);
        }
        pendingDebounce = new Runnable() {
            @Override
            public void run() {
                pendingDebounce = null;
                prefetchAsync(app);
            }
        };
        MAIN.postDelayed(pendingDebounce, DEBOUNCE_MS);
    }

    /** 异步预生成一批题（每次弹题解锁后调用一次，保证下一轮有新题） */
    public static void prefetchAsync(final Context c) {
        if (!isConfigured(c)) return;
        // 年级/学期/难度在调用线程就取快照，跟着这次请求走到入库那一步。
        // 出题要跑 2 轮模型（出题 + 答案核对，单轮超时 90s），
        // 期间家长随时可能切设置；不把快照带到底，五年级上学期的题会被写进初二下学期的题库。
        final int grade = Store.grade(c);
        final int sem = Store.semester(c);
        final int diff = Store.difficulty(c);
        final String key = gradeDiffKey(grade, sem, diff);
        // 当天还欠一次"三科补齐"：把这一轮让给每日缓存任务（每科 3 题，比只补一科均衡），
        // 并且不抢 pendingKey——抢了它，排队中的每日任务就永远跑不起来了。
        final String today = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
        if (!today.equals(Store.aiCacheDay(c, grade, sem))) {
            if (!dailyPending) dailyRefreshAsync(c);
            return;
        }
        if (key.equals(pendingKey)) {
            Log.i(TAG, "已有 " + key + " 的出题任务在跑/排队，跳过重复预取");
            return;
        }
        pendingKey = key;
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!key.equals(gradeDiffKey(Store.grade(c), Store.semester(c), Store.difficulty(c)))) {
                        Log.i(TAG, "排队期间年级/学期/难度已变化，丢弃这次预取（" + key + "）");
                        return;
                    }
                    String subject = SUBJECTS[RND.nextInt(SUBJECTS.length)];
                    // 一次只要 5 道：思考型模型出 10 道必然超过读超时（以前 90 秒就卡在这里），
                    // 而且题库里已有内置题兜底，不需要一次灌满
                    List<Question> qs = request(c, grade, sem, diff, subject, 5);                    if (qs.isEmpty()) return;
                    if (!key.equals(gradeDiffKey(Store.grade(c), Store.semester(c), Store.difficulty(c)))) {
                        Log.w(TAG, "出题期间年级/学期/难度变了，本批题丢弃，避免串题库（原 " + key + "）");
                        return;
                    }
                    QuestionBank.addAiQuestions(c, qs, grade, sem, diff);
                    Log.i(TAG, "AI 出题成功：" + qs.size() + " 道（" + key + " " + subject + "）");
                } catch (Exception e) {
                    Store.setAiLastError(c, e.getMessage());
                    Log.w(TAG, "AI 出题失败，使用本地题库兜底：" + e.getMessage());
                } finally {
                    if (key.equals(pendingKey)) pendingKey = "";
                }
            }
        });
    }

    /**
     * 每日离线缓存更新：每天第一次启动时，让 AI 给三个科目各出 3 题
     * （带答案解析）写入本地题库，保证断网/断网日也有新题可用。
     * 只有真正调通接口才更新日期标记，失败下次启动自动重试。
     */
    public static void dailyRefreshAsync(final Context c) {
        if (!isConfigured(c)) return;
        final int grade = Store.grade(c);
        final int sem = Store.semester(c);
        final int diff = Store.difficulty(c);
        final String key = gradeDiffKey(grade, sem, diff);
        final String today = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
        // 标记按 年级+学期 分开记：否则 5 年级上学期那天更新过，切到下学期当天就不会再生成，
        // 新题库全空 → 只能抽到内置题
        if (today.equals(Store.aiCacheDay(c, grade, sem))) {
            Log.i(TAG, "今日 " + key + " 离线题库缓存已更新过，跳过");
            return;
        }
        if (dailyPending) return;   // 已有一份在排队/执行，别重复排
        dailyPending = true;
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                // owned：只有真正抢到 pendingKey 的任务才有资格在结束时清掉它。
                // 否则"看到别人在跑就提前 return"的那条路径会把别人的占位标记清掉，
                // 同一题库被重复排进队列、白打一轮接口。
                final boolean[] owned = {false};
                try {
                    if (!key.equals(gradeDiffKey(Store.grade(c), Store.semester(c), Store.difficulty(c)))) {
                        Log.i(TAG, "排队期间年级/学期/难度已变化，丢弃这次每日缓存更新（" + key + "）");
                        return;
                    }
                    if (key.equals(pendingKey)) {
                        return;   // 预取任务已按当前题库在出题，别重复打接口
                    }
                    pendingKey = key;
                    owned[0] = true;
                    int okCalls = 0, added = 0;
                    for (String subject : SUBJECTS) {
                        if (!key.equals(gradeDiffKey(Store.grade(c), Store.semester(c), Store.difficulty(c)))) {
                            Log.w(TAG, "更新期间年级/学期变了，本批题丢弃（原 " + key + "）");
                            return;
                        }
                        // 每天每科补 3 题（共 9 题）：题库上限 45 题，超限先淘汰最旧 AI 题
                        List<Question> qs = request(c, grade, sem, diff, subject, 3);
                        okCalls++;
                        if (!qs.isEmpty()) {
                            QuestionBank.addAiQuestions(c, qs, grade, sem, diff);
                            added += qs.size();
                        }
                    }
                    // 只要今天真正调通了一轮接口就打标记（哪怕题全被去重掉）：
                    // 否则每次启动/每次解锁都会重打一遍 3 科，白耗额度
                    if (okCalls > 0) {
                        Store.setAiCacheDay(c, grade, sem, today);
                        Log.i(TAG, "每日离线缓存更新完成：" + key + " 调通 " + okCalls
                                + "/" + SUBJECTS.length + " 科，入库 " + added + " 道（含答案解析）");
                    }
                } catch (Exception e) {
                    // 断网/超时也让主界面能看到原因，而不是只剩一句"每天更新失败"
                    Store.setAiLastError(c, e.getMessage());
                    Log.w(TAG, "每日缓存更新失败，下次启动重试：" + e.getMessage());
                } finally {
                    dailyPending = false;
                    if (owned[0] && key.equals(pendingKey)) pendingKey = "";
                }
            }
        });
    }

    private static List<Question> request(Context c, int grade, int semester, int diff, String subject, int count)
            throws Exception {
        String gradeLabel = gradeLabel(grade);
        String termLabel = Store.semesterName(semester);
        String subjectName = QuestionBank.subjectName(subject);
        String diffRule;
        if (diff <= 1) {
            diffRule = "基础巩固题：考课本概念和最基础的计算/字词，多数孩子应能独立做对（约 70% 正确率）";
        } else if (diff >= 3) {
            diffRule = "拔高题：需要两步以上推理或易错点辨析，比作业稍难一档（约 30% 正确率），但不超纲、不出偏题怪题";
        } else {
            diffRule = "中等题：与课本例题、课后作业同难度（约 50% 正确率）";
        }

        String prompt = "你是一名" + gradeLabel + "老师。现在是" + termLabel
                + "，请为" + gradeLabel + termLabel + "学生出 " + count
                + " 道" + subjectName + "选择题。要求：\n"
                + "1. 只考查" + gradeLabel + termLabel + "所学的知识点（上学期≈前半本教材，下学期≈后半本教材），不超纲、不出下学期才教的内容；\n"
                + "2. 难度定位：" + diffRule + "；\n"
                + "3. 每题只有一个正确答案，内容准确无误；\n"
                + "4. 选项文本中不要出现 A、B、C、D 等字母编号；\n"
                + "5. 题干、选项、解析里都不要出现英文双引号，需要引用时用「」；\n"
                + "6. 只输出 JSON 数组，不要输出任何其他文字，格式：\n"
                + "[{\"q\":\"题干\",\"options\":[\"选项1\",\"选项2\",\"选项3\",\"选项4\"],\"answer\":0,\"explain\":\"一句话解析\"}]";

        // 把已出过的题干一起发过去：不提示的话，温度 0.9 下模型会反复挑同一批"常考题"
        // （"12×4=?"、"下列词语中没有错别字的一项"…），入库时只能靠文本去重筛掉，一批 5 题常常只剩 2 题。
        List<String> used = QuestionBank.existingStems(c, 15);
        if (!used.isEmpty()) {
            StringBuilder sb = new StringBuilder(prompt);
            sb.append("\n7. 下面这些题已经出过，本次不要出（题干意思相近就算重复，换个数字出同一道题也算重复）：\n");
            for (String s : used) {
                sb.append("- ").append(s).append("\n");
            }
            sb.append("8. ").append(count).append(" 道题要覆盖不同单元 / 不同知识点，不要都考同一个知识点。");
            prompt = sb.toString();
        }

        JSONObject body = new JSONObject();
        body.put("temperature", 0.9);
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "user").put("content", prompt));
        body.put("messages", msgs);

        long t0 = System.currentTimeMillis();
        Resp resp = complete(c, body, false, false, READ_TIMEOUT_MS);
        // 题目出处跟着"出题那次调用"走（验算只是复核，不改归属），题卡上会显示平台 + 模型
        List<Question> qs = parse(resp.content, subject, resp.provider, resp.model);
        // 主界面的"最近一次成功"只记真正出题成功的那一次，不被后面的验算请求覆盖成别的平台；
        // 一行文案只显示 AI1 / AI2，不暴露平台名和模型名
        Store.setAiLastOk(c, alias(c, resp.provider) + " 出题成功");
        // 答案核对：flash 级模型偶尔会给出与自身解析矛盾的答案（如 12×4×3=144 却标 48），
        // 出完题再让它快速核对一遍答案与选项是否匹配，错了就修正。
        // 只做一轮、只查答案，不深度推演；整条链路有时间预算：出题已花掉大半时直接先入库。
        if (!qs.isEmpty() && System.currentTimeMillis() - t0 < VERIFY_BUDGET_MS) {
            List<Question> fixed = verify(c, grade, subject, qs, resp.provider, resp.model);
            // 验算返回空（断网、模型吐了空数组）时沿用原题：不能把刚出好的一批整批丢掉
            if (fixed != null && !fixed.isEmpty()) {
                qs = fixed;
            } else if (fixed != null) {
                Log.w(TAG, "验算没返回可用题目，沿用原始 " + qs.size() + " 题");
            }
        } else if (!qs.isEmpty()) {
            Log.w(TAG, "出题耗时过长，跳过验算直接入库（" + qs.size() + " 题）");
        }
        // 入库前统一打上"出题时的难度"：家长调难度后，旧难度的 AI 题才能被清出去
        List<Question> tagged = new ArrayList<Question>(qs.size());
        for (Question q : qs) tagged.add(q.withDiff(diff));
        return tagged;
    }

    /** 一次成功的模型调用：正文 + 真实出处（平台 / 模型 id），用于题卡上的来源标记 */
    private static final class Resp {
        final String content;
        final String provider;
        final String model;

        Resp(String content, String provider, String model) {
            this.content = content;
            this.provider = provider;
            this.model = model;
        }
    }

    /** 出题读超时：主模型 glm-5.3-flash 带思考，一批 5 题实测 30~70 秒，60 秒必然误杀 */
    private static final int READ_TIMEOUT_MS = 90000;
    /** 出题 + 答案核对的整体预算，超出就跳过核对直接入库 */
    private static final long VERIFY_BUDGET_MS = 55000L;

    /**
     * 模型调用：主平台按"快模型 → 配置模型 → 备用模型 → 快模型"依次尝试，
     * 全部失败后（若配置了备用平台 Key）再打备用平台的候选模型。
     * 429（限流）/ 超时 / 服务异常的模型进入冷却期，并按连续失败次数指数退避
     * （5→10→20…分钟，封顶 60 分钟），避免限流期每次都白等一整个超时。
     * 收到 1305（平台级过载）时直接短路跳下一家平台——同平台剩余候选大概率一并被拒。
     *
     * @param deep       是否深度思考（验算用 true）
     * @param preferFast 是否优先用快模型（验算用 true：验算只是复核，快模型足够，省一半时间）
     */
    private static Resp complete(Context c, JSONObject body, boolean deep, boolean preferFast,
                                 int timeoutMs) throws Exception {
        Exception last = null;
        for (Provider p : providers(c, preferFast)) {
            for (String model : p.models) {
                if (cooling(p, model)) {
                    Log.i(TAG, "[" + p.tag + "] 模型 " + model + " 冷却中，跳过");
                    continue;
                }
                try {
                    body.put("model", model);
                    // 换模型/换平台前先清掉上一家的专属字段：否则智谱的 thinking 会被带到
                    // 硅基流动的 Qwen 请求里（反之亦然），平台可能因为不认识该字段整单拒绝，
                    // 表现成"这家也不可用"——两家的数据在同一个 body 上串了。
                    body.remove("thinking");
                    body.remove("reasoning_effort");
                    body.remove("enable_thinking");
                    applyThinking(body, model, deep);
                    String r = chat(c, p.baseUrl, p.apiKey, body, timeoutMs);
                    Log.i(TAG, "[" + p.tag + "] 模型 " + model + " 请求成功");
                    resetFails(p, model);
                    Store.setAiLastError(c, "");
                    return new Resp(r, p.tag, model);
                } catch (Exception e) {
                    last = e;
                    String msg = e.getMessage() == null ? "" : e.getMessage();
                    long cd = cooldownFor(msg, bumpFails(p, model));
                    markCooling(p, model, cd);
                    Log.w(TAG, "[" + p.tag + "] 模型 " + model + " 失败（" + msg + "），冷却 "
                            + (cd / 60000) + " 分钟");
                    // 1305 = 平台全局过载，同一平台剩下的候选模型大概率也被拒，直接换下一家
                    if (msg.contains("1305")) {
                        Log.i(TAG, "[" + p.tag + "] 平台过载（1305），跳过该平台剩余候选模型");
                        break;
                    }
                }
            }
        }
        throw last != null ? last : new IllegalStateException("所有模型都处于冷却期");
    }

    /**
     * 主平台免费快模型兜底链（主模型 + 第一降级都失败后才轮到，按实测稳定性排序）。
     * 4.7-flash 间歇性 1305 平台过载，放最后；4.5-flash 能通但负载高（实测 36s），放中间。
     */
    private static final String[] FAST_MODELS = {"glm-4.5-flash", "glm-4.7-flash"};
    /** 备用平台的默认候选模型（免费，按出题质量排序；ai_model2 会插到最前） */
    private static final String[] BACKUP_MODELS = {"Qwen/Qwen3-8B", "THUDM/glm-4-9b-chat"};

    /** 一个 OpenAI 兼容平台：端点 + Key + 一串候选模型 */
    private static class Provider {
        final String baseUrl;
        final String apiKey;
        String tag;
        final List<String> models = new ArrayList<String>();

        Provider(String url, String key) {
            baseUrl = url;
            apiKey = key;
            tag = hostTag(url);
        }
    }

    /**
     * 组装降级链：主平台候选在前，备用平台（配了 key 才算数）殿后。
     * 两个平台都被指到同一个域名时（用户配错 base_url2 的情况），给后者加 #2 后缀——
     * 否则两家的冷却表、失败计数、题卡来源标记会共用一个 key，一家限流把另一家也标成冷却。
     */
    private static List<Provider> providers(Context c, boolean preferFast) {
        List<Provider> out = new ArrayList<Provider>();
        // 主平台没配 Key（只配了备用平台）时整个跳过，省掉一轮必然 401 的请求
        if (!Store.aiKey(c).isEmpty()) {
            Provider main = new Provider(Store.aiBaseUrl(c), Store.aiKey(c));
            if (preferFast) {
                // 验算这类轻量调用：先走免费快模型（glm-4-flash 响应快、不思考），主模型殿后
                addModel(main.models, BuildConfig.AI_MODEL_FALLBACK);
                for (String m : FAST_MODELS) addModel(main.models, m);
            }
            // 出题：主模型（资源包 glm-5.3-flash）最优先 → 4.7-flash → 免费档兜底
            addModel(main.models, Store.aiModel(c));
            addModel(main.models, BuildConfig.AI_MODEL_FALLBACK);
            for (String m : FAST_MODELS) addModel(main.models, m);
            out.add(main);
        }

        // 备用平台：key 为空表示未配置，跳过（不报错——没配备用平台是正常用法）
        String key2 = Store.aiKey2(c);
        if (!key2.isEmpty()) {
            Provider bak = new Provider(Store.aiBaseUrl2(c), key2);
            addModel(bak.models, Store.aiModel2(c));
            for (String m : BACKUP_MODELS) addModel(bak.models, m);
            if (!bak.models.isEmpty()) {
                for (Provider p : out) {
                    if (p.tag.equals(bak.tag)) {
                        bak.tag = bak.tag + "#2";
                        break;
                    }
                }
                out.add(bak);
            }
        }
        return out;
    }

    private static String hostTag(String url) {
        if (url.contains("bigmodel.cn")) return "智谱";
        if (url.contains("siliconflow")) return "硅基流动";
        if (url.contains("aliyuncs")) return "百炼";
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return "未知平台";
        }
    }

    /** 冷却/失败计数的键：带上平台 tag，不同平台的同名模型互不干扰 */
    private static String coolKey(Provider p, String model) {
        return p.tag + "/" + model;
    }

    /** 模型 → 冷却截止时间戳（跨平台共用一张表，键含平台 tag） */
    private static final Map<String, Long> COOLING = new HashMap<String, Long>();
    /** 模型连续失败次数（成功即清零），用于指数退避 */
    private static final Map<String, Integer> FAILS = new HashMap<String, Integer>();

    private static void addModel(List<String> list, String m) {
        if (m == null || m.trim().isEmpty()) return;
        if (!list.contains(m)) list.add(m);
    }

    private static boolean cooling(Provider p, String model) {
        synchronized (COOLING) {
            String k = coolKey(p, model);
            Long until = COOLING.get(k);
            if (until == null) return false;
            if (System.currentTimeMillis() >= until) {
                COOLING.remove(k);
                return false;
            }
            return true;
        }
    }

    private static void markCooling(Provider p, String model, long ms) {
        synchronized (COOLING) {
            COOLING.put(coolKey(p, model), System.currentTimeMillis() + ms);
        }
    }

    private static int bumpFails(Provider p, String model) {
        String k = coolKey(p, model);
        synchronized (FAILS) {
            Integer n = FAILS.get(k);
            int v = (n == null ? 0 : n) + 1;
            FAILS.put(k, v);
            return v;
        }
    }

    private static void resetFails(Provider p, String model) {
        synchronized (FAILS) {
            FAILS.remove(coolKey(p, model));
        }
    }

    /**
     * 依据失败原因决定冷却时长：429 限流短冷却 + 按连续失败次数指数退避（×2 递增，封顶 60 分钟）；
     * 403/400 是确定性错误（权限、模型名不对），退避没有意义，固定 60 分钟。
     */
    private static long cooldownFor(String msg, int fails) {
        long base;
        if (msg.contains("429") || msg.contains("1302") || msg.contains("1305")) {
            base = 5 * 60 * 1000L;                                                        // 限流
        } else if (msg.contains("401") || msg.contains("403") || msg.contains("1220")
                || msg.contains("1000") || msg.contains("1113")) {
            // Key 无效 / 无权限 / 余额不足 / 鉴权失败：重试一百次也是同样结果，
            // 冷却 60 分钟，别每分钟去打一遍（还会把"最近一次失败"刷成永远最新）
            return 60 * 60 * 1000L;
        } else if (msg.contains("400") || msg.contains("1210") || msg.contains("1211")) {
            return 60 * 60 * 1000L;                                                       // 参数/模型名不对
        } else if (msg.contains("timeout") || msg.contains("Timeout")) {
            base = 3 * 60 * 1000L;                                                        // 超时
        } else {
            base = 60 * 1000L;                                                            // 网络抖动
        }
        long factor = 1L << Math.min(Math.max(fails - 1, 0), 6);
        return Math.min(base * factor, 60 * 60 * 1000L);
    }

    /**
     * 思考参数只发给认识的模型——别家平台收到不认识的字段可能整单被拒：
     * glm-5.3 系列始终思考、强度用顶层 reasoning_effort 字段（low/high/max，默认 max！
     * 不发就是 max 深度思考，出 1 道题实测 31 秒 vs low 档 4 秒，超时全是它造成的）；
     * glm-4 系列可以关闭思考；硅基流动的 Qwen3 系列非流式请求必须显式关思考。
     * 出题/验算用 low（够快且质量足够），deep 预留 high。
     */
    private static void applyThinking(JSONObject body, String model, boolean deep) throws Exception {
        if (model.startsWith("glm")) {
            if (model.startsWith("glm-5")) {
                JSONObject t = new JSONObject();
                t.put("type", "enabled");
                body.put("thinking", t);
                body.put("reasoning_effort", deep ? "high" : "low");
            } else {
                JSONObject t = new JSONObject();
                t.put("type", deep ? "enabled" : "disabled");
                body.put("thinking", t);
            }
        } else if (model.toLowerCase(Locale.ROOT).contains("qwen3")) {
            body.put("enable_thinking", false);
        }
    }

    /** 发起一次对话请求，返回模型文本（baseUrl/apiKey 由所属平台传入） */
    private static String chat(Context c, String baseUrl, String apiKey, JSONObject body,
                               int readTimeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            os.close();

            int code = conn.getResponseCode();
            // 服务器响应头里的 Date 是权威时间：记录它，用于纠正电视上报错的系统时钟
            License.noteHttpDate(c, conn.getHeaderField("Date"));
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            r.close();
            if (code >= 400) {
                throw new IllegalStateException("HTTP " + code + ": " + sb);
            }

            JSONObject resp = new JSONObject(sb.toString());
            return resp.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content");
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 验算（一轮）：只核对每题的 answer 是否正确、是否和选项匹配。
     * 答案错的修正 answer/explain；无法确定的 answer=-1（parse 会自动丢弃）。
     * 验算失败（网络/格式）时返回 null，调用方沿用原题——宁可出旧题也不能没题。
     */
    private static List<Question> verify(Context c, int grade, String subject, List<Question> qs,
                                         String source, String model) {
        return verifyOnce(c, grade, subject, qs, 0.1, source, model);
    }

    /** 单轮验算。只查答案对不对、和选项匹不匹配，不做深度推演 */
    private static List<Question> verifyOnce(Context c, int grade, String subject, List<Question> qs,
                                             double temp, String source, String model) {
        try {
            JSONArray arr = new JSONArray();
            for (Question q : qs) {
                JSONObject o = new JSONObject();
                o.put("q", q.q);
                JSONArray opts = new JSONArray();
                for (String s : q.options) opts.put(s);
                o.put("options", opts);
                o.put("answer", q.answer);
                o.put("explain", q.explain);
                arr.put(o);
            }
            String prompt = "下面是 " + qs.size() + " 道小学/初中选择题（JSON 数组）。请逐题快速核对，只做一件事：\n"
                    + "验证每题的 answer（options 的下标，从 0 开始）是否就是正确答案、是否与题干和四个选项匹配。\n"
                    + "1. 答案正确：原样保留，什么都不要改；\n"
                    + "2. 答案错误：把 answer 改成正确选项的下标，并把 explain 改成一句支持新答案的解析；\n"
                    + "3. 题干或选项本身有误、无法确定答案：把该题 answer 改为 -1；\n"
                    + "4. 不要修改 q 和 options 的内容，不要增删题，不要做其他检查；\n"
                    + "5. 字符串内不要出现英文双引号，需要引用时用「」；\n"
                    + "6. 只输出修正后的完整 JSON 数组，不要输出任何其他文字。\n"
                    + arr.toString();

            JSONObject body = new JSONObject();
            body.put("temperature", temp);
            JSONArray msgs = new JSONArray();
            msgs.put(new JSONObject().put("role", "user").put("content", prompt));
            body.put("messages", msgs);

            // 验算优先用快模型、且不深度思考：只是复核，没必要每次再等思考型模型一分钟
            Resp vr = complete(c, body, false, true, 40000);
            List<Question> fixed = parse(vr.content, subject, source, model);
            Log.i(TAG, "AI 验算完成（temp=" + temp + "，由[" + vr.provider + "] " + vr.model
                    + " 复核）：原 " + qs.size() + " 题，剩 " + fixed.size() + " 题");
            return fixed;
        } catch (Exception e) {
            Log.w(TAG, "AI 验算失败，沿用原题：" + e.getMessage());
            return null;
        }
    }

    public static String gradeLabel(int grade) {
        if (grade <= 6) return "小学" + grade + "年级";
        if (grade == 7) return "初中一年级（初一）";
        if (grade == 8) return "初中二年级（初二）";
        return "初中三年级（初三）";
    }

    /**
     * 从模型返回的文本里提取题目。
     * 先按标准 JSON 解析；失败时（flash 模型经常在中文里塞未转义的英文双引号，
     * 导致 JSON 直接不合法）改用正则容错解析，把能救的题救回来。
     */
    private static List<Question> parse(String content, String subject, String source, String model)
            throws Exception {
        try {
            return parseStrict(content, subject, source, model);
        } catch (Exception e) {
            List<Question> loose = parseLoose(content, subject, source, model);
            if (loose.isEmpty()) throw e;
            Log.i(TAG, "JSON 不合法（" + e.getMessage() + "），容错解析救回 " + loose.size() + " 题");
            return loose;
        }
    }

    private static List<Question> parseStrict(String content, String subject, String source,
                                              String model) throws Exception {
        int s = content.indexOf('[');
        int e = content.lastIndexOf(']');
        if (s < 0 || e <= s) {
            throw new IllegalStateException("AI 未返回 JSON 数组：" + brief(content));
        }
        JSONArray arr = new JSONArray(content.substring(s, e + 1));
        List<Question> out = new ArrayList<Question>();
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                JSONArray opts = o.getJSONArray("options");
                if (opts.length() < 2) continue;
                List<String> ol = new ArrayList<String>();
                for (int j = 0; j < opts.length(); j++) {
                    ol.add(opts.getString(j));
                }
                Question q = validate(subject, o.getString("q"), ol,
                        o.getInt("answer"), o.optString("explain", ""), source, model);
                if (q != null) out.add(q);
            } catch (Exception ignored) {
                // 单题格式有问题就跳过，不影响其他题
            }
        }
        return out;
    }

    /** 字段级正则：即使字符串里有未转义的引号，也能靠 "options"/"answer"/"explain" 这些锚点切出来 */
    private static final java.util.regex.Pattern LOOSE = java.util.regex.Pattern.compile(
            "\"q\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"options\"\\s*:\\s*\\[(.*?)\\]\\s*,"
                    + "\\s*\"answer\"\\s*:\\s*(-?\\d+)\\s*,\\s*\"explain\"\\s*:\\s*\"(.*?)\"\\s*\\}",
            java.util.regex.Pattern.DOTALL);

    private static List<Question> parseLoose(String content, String subject, String source,
                                             String model) {
        List<Question> out = new ArrayList<Question>();
        java.util.regex.Matcher m = LOOSE.matcher(content);
        while (m.find()) {
            try {
                String qText = m.group(1).trim();
                List<String> ol = new ArrayList<String>();
                for (String s : m.group(2).split("\"\\s*,\\s*\"")) {
                    String v = s.trim();
                    if (v.startsWith("\"")) v = v.substring(1);
                    if (v.endsWith("\"")) v = v.substring(0, v.length() - 1);
                    if (!v.isEmpty()) ol.add(v);
                }
                Question q = validate(subject, qText, ol,
                        Integer.parseInt(m.group(3)), m.group(4), source, model);
                if (q != null) out.add(q);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** 日志里只留一小段返回内容，别把整段 JSON 打进 logcat */
    private static String brief(String s) {
        if (s == null) return "";
        String t = s.replace("\n", " ").trim();
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }

    /** 统一的题目合法性校验：选项不重复、答案下标合法、解析不能自相矛盾 */
    private static Question validate(String subject, String qText, List<String> ol, int ans,
                                     String explain, String source, String model) {
        if (qText == null || qText.trim().isEmpty() || ol.size() < 2) return null;
        if (ans < 0 || ans >= ol.size()) return null;
        // AI 偶尔会生成重复选项（如两个 Childs），这种题无法作答，整题丢弃
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (String opt : ol) {
            if (!seen.add(opt.trim().toLowerCase(Locale.ROOT))) return null;
        }
        // 本地一致性兜底：解析里明确写了"正确答案：X"却和 answer 选项对不上，
        // 说明这题自相矛盾（AI 最典型的错误形态），整题丢弃。
        // 注意各家模型写法不同："正确答案：X" / "正确答案是 X" / "正确答案为X" / "正确答案 B"，都要能剥掉前缀，
        // 否则"是 X"会和选项"X"字符串不等，把整批好题误杀（硅基流动的 Qwen 就是这种写法）。
        int p = explain.indexOf("正确答案");
        if (p >= 0) {
            String stated = explain.substring(p + 4).trim()
                    .replaceFirst("^[：:，,、\\s]+", "")
                    .replaceFirst("^(应该选的答案是|应该选|应选|该选|是|为)", "")
                    .trim();
            stated = stated.split("[，,。；;（）()]")[0].trim();
            if (!stated.isEmpty() && !matchAnswer(stated, ol, ans)) {
                Log.i(TAG, "丢弃自相矛盾的题（解析说" + stated + "，answer 是 " + ol.get(ans) + "）");
                return null;
            }
        }
        // 稳定 id：同一道题（科目+题干）在任何一次生成/缓存重载后 id 都相同，错题本不会失联
        return new Question("ai_" + QuestionBank.stableId(subject, qText.trim()),
                subject, qText.trim(), ol, ans, explain, source, model);
    }

    /**
     * 解析里的"正确答案"和 answer 下标是否一致。
     * 比较时忽略空白 / 标点 / 大小写；解析写成字母编号（A~D）时按下标换算——
     * 判错会丢题，判宽只会混进少量错题（后面还有验算一轮兜着），所以这里宁可宽一点。
     */
    private static boolean matchAnswer(String stated, List<String> ol, int ans) {
        String s = norm(stated);
        if (s.isEmpty()) return true;
        if (s.equals(norm(ol.get(ans)))) return true;
        if (s.length() == 1) {
            char ch = Character.toUpperCase(s.charAt(0));
            if (ch >= 'A' && ch <= 'D') return (ch - 'A') == ans;
        }
        // "选项2" / "第3项" 这类描述：只认数字部分
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(选项|第)?\\s*([1-4])\\s*(项|个|条)?$").matcher(stated.trim());
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(2)) - 1 == ans;
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }

    /** 比较用的归一化：去掉空白与常见标点，统一小写 */
    private static String norm(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", "")
                .replaceAll("[。．.，,；;：:！!？?\"'“”‘’「」『』]", "")
                .toLowerCase(Locale.ROOT);
    }
}
