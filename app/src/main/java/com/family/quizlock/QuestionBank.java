package com.family.quizlock;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 每个年级的每个学期一份"本地题库"（g5_s1 = 五年级上学期），容量 {@link #PER_GRADE_BANK} 道，滚动更新：
 *
 * <pre>
 *   内置题库（assets/questions/gradeN.json，一到九年级各一份，上下学期共用同一份基线）
 *        ↓ 首次使用某个"年级+学期"时播种（每科最多 10 道）
 *   该题库（持久化在 quizlock_bank/gradebank_gN_sS）
 *        ↓ AI 出题成功 → 追加进题库（带答案解析，AI 按学期出题）
 *        ↓ 超过 45 道 → 先淘汰最旧的 AI 题，加几道就删几道
 * </pre>
 *
 * 抽题顺序：25% 概率从错题本复现 → 优先当前难度匹配的题 → 其余从「该题库 + 家长自建题」随机，避开最近出过的 15 题。
 * 缓存策略：只保留最近使用的 8 份题库（年级×学期），超过 30 天没被选过的自动清掉。
 */
public final class QuestionBank {
    private static final String TAG = "QuestionBank";
    private static final Random RND = new Random();

    /** 每份题库的容量（内置播种 + AI 新题一起算） */
    public static final int PER_GRADE_BANK = 45;
    /** 播种时每个科目最多取多少道内置题（30 / 3 科） */
    private static final int SEED_PER_SUBJECT = 10;

    /**
     * 同时保留几份题库（年级×学期）。每份约 15KB，留 8 份可以覆盖 4 个年级上下学期
     * 来回切换不丢题，对电视存储毫无压力。
     */
    public static final int MAX_CACHED_BANKS = 8;
    public static final int CACHE_IDLE_DAYS = 30;

    private static final String BANK_FILE = "quizlock_bank";
    private static final String KEY_CUSTOM = "custom_bank";
    private static final String KEY_BANK = "gradebank_g";
    /** 内置题库（assets/questions/gradeN.json）版本号：改了内置题就 +1，升级时会合并进老用户的题库 */
    private static final int BUILTIN_VER = 2;
    private static final String KEY_BUILTIN_VER = "builtin_bank_ver";

    /** 年级题库使用顺序/时间戳（里面只留 order / used_<key>） */
    private static final String META_FILE = "quizlock_offline";

    /** 题库键：gradebank_g5_s1 = 五年级上学期 */
    private static String bankKey(int grade, int semester) {
        return KEY_BANK + grade + "_s" + semester;
    }

    /** 已加载到内存的题库（题库键 → 题目） */
    private static final Map<String, List<Question>> BANKS = new HashMap<String, List<Question>>();
    /** 当前题库（BANKS 里的那份，直接改它） */
    private static List<Question> current = new ArrayList<Question>();
    private static String currentKey = "";

    /** 家长通过局域网面板自己加的题（持久化在 quizlock_bank/custom_bank） */
    private static final List<Question> custom = new ArrayList<Question>();
    private static boolean inited = false;

    private QuestionBank() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(BANK_FILE, Context.MODE_PRIVATE);
    }

    /** 稳定 id：由"科目+题干"算 MD5，重载/重启后不变，错题本不会失联 */
    public static String stableId(String subject, String q) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest((subject + "|" + q).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString((subject + "|" + q).hashCode());
        }
    }

    /** 首次启动：加载自建题；之后每次调用只做"当前年级是否变了"的检查 */
    public static synchronized void ensure(Context c) {
        if (!inited) {
            inited = true;
            loadCustom(c);
            syncBuiltinVersion(c);
        }
        syncGrade(c);
    }

    /**
     * 内置题库升级：assets 里的题改了（修错题、补题），老用户不会重新播种——
     * 因为题库一旦非空就再也不会走 seed()。这里按版本号做一次合并：
     * <ul>
     *   <li>同一个 id 的题：用 assets 里的新内容覆盖（答案/解析改了也能同步过来）</li>
     *   <li>assets 里新增的题：补进题库（每科仍受 {@link #SEED_PER_SUBJECT} 限制）</li>
     *   <li>AI 题、自建题、家长删掉的题都不动</li>
     * </ul>
     * 只处理"已经用过、磁盘上有题库"的年级，不给没用过的年级凭空建库。
     */
    private static void syncBuiltinVersion(Context c) {
        int old = prefs(c).getInt(KEY_BUILTIN_VER, 0);
        if (old == BUILTIN_VER) return;
        int updated = 0, added = 0, banks = 0;
        for (int g = 1; g <= 9; g++) {
            for (int s = 1; s <= 2; s++) {
                String key = bankKey(g, s);
                if (prefs(c).getString(key, null) == null) continue;   // 这份题库还没用过
                List<Question> bank = loadBank(c, key, g);
                if (bank.isEmpty()) continue;
                List<Question> fresh = seed(c, g);          // 重新读一遍 assets（带 g{grade}_ 前缀）
                if (fresh.isEmpty()) continue;
                boolean changed = false;
                Map<String, Integer> perSubject = new HashMap<String, Integer>();
                for (Question q : bank) {
                    if (!q.isAi()) {
                        Integer n = perSubject.get(q.subject);
                        perSubject.put(q.subject, n == null ? 1 : n + 1);
                    }
                }
                for (Question nq : fresh) {
                    int idx = indexOfId(bank, nq.id);
                    if (idx >= 0) {
                        Question oq = bank.get(idx);
                        if (!sameContent(oq, nq)) {
                            bank.set(idx, nq);              // 同 id：内容以 assets 为准
                            updated++;
                            changed = true;
                        }
                    } else {
                        Integer n = perSubject.get(nq.subject);
                        int cnt = n == null ? 0 : n;
                        if (cnt >= SEED_PER_SUBJECT) continue;
                        bank.add(nq);
                        perSubject.put(nq.subject, cnt + 1);
                        added++;
                        changed = true;
                    }
                }
                if (changed) {
                    trim(bank);
                    persistBank(c, key, bank);
                    banks++;
                }
            }
        }
        prefs(c).edit().putInt(KEY_BUILTIN_VER, BUILTIN_VER).apply();
        Log.i(TAG, "内置题库升级 v" + old + "→v" + BUILTIN_VER + "：更新 " + updated
                + " 题、新增 " + added + " 题（涉及 " + banks + " 份题库）");
    }

    private static int indexOfId(List<Question> list, String id) {
        for (int i = 0; i < list.size(); i++) {
            if (id.equals(list.get(i).id)) return i;
        }
        return -1;
    }

    /** 题干/选项/答案/解析任一变化即视为需要更新 */
    private static boolean sameContent(Question a, Question b) {
        return a.q.equals(b.q) && a.answer == b.answer
                && a.options.equals(b.options) && a.explain.equals(b.explain);
    }

    /** 年级/学期变了就换一套题库（内存里有就直接用，没有再从磁盘/内置题播种） */
    private static void syncGrade(Context c) {
        String key = bankKey(Store.grade(c), Store.semester(c));
        if (key.equals(currentKey)) return;
        currentKey = key;
        current = loadBank(c, key, Store.grade(c));
        touchBank(c, key);
    }

    /** 切换年级后调用 */
    public static synchronized void onGradeChanged(Context c) {
        currentKey = "";
        syncGrade(c);
    }

    /** 切换学期后调用（上/下学期题库独立，AI 也按学期出题） */
    public static synchronized void onSemesterChanged(Context c) {
        currentKey = "";
        syncGrade(c);
    }

    /**
     * 难度改了：题库本身不按难度分，但 AI 题带着出题时的难度标记。
     * 这里【不再删除】旧难度的 AI 题——家长来回切难度时，删了就要重新请求，
     * 切回去又是一批新请求，题库反而被掏空。改用"抽题降权"：
     * {@link #randomQuestion} 优先抽当前难度匹配的题，不匹配的留在库里当储备，
     * 切回原难度立即可用，零请求零等待。
     * 真正的容量回收交给 {@link #trim}（题库超限时从最旧开始淘汰）。
     */
    public static synchronized void onDifficultyChanged(Context c) {
        int diff = Store.difficulty(c);
        int matched = 0;
        for (Question q : current) {
            if (q.isAi() && q.diff == diff) matched++;
        }
        Log.i(TAG, "难度改为 " + diff + "，题库保留不清理（" + current.size()
                + " 题，其中 AI " + aiCount(current) + "、当前难度匹配 " + matched + "）");
    }

    // ---------- 题库加载 / 播种 / 持久化 ----------

    private static List<Question> loadBank(Context c, String key, int grade) {
        List<Question> cached = BANKS.get(key);
        if (cached != null) return cached;

        List<Question> list = new ArrayList<Question>();
        String raw = prefs(c).getString(key, null);
        if (raw != null) {
            try {
                list.addAll(parseBank(new JSONArray(raw)));
            } catch (Exception e) {
                Log.w(TAG, "题库解析失败，用内置题重建：" + key, e);
                list.clear();
            }
        }
        if (list.isEmpty()) {
            list.addAll(seed(c, grade));
            // 空题库不落盘（7~9 年级没有内置题），免得占着缓存名额
            if (!list.isEmpty()) persistBank(c, key, list);
        } else {
            // 老版本题库里可能已经躺着重复题（AI 换个标点把同一道题又出了一遍）：
            // 加载时顺手清一遍，清完再落盘，家长不用手动重置题库
            int dup = dedupe(list);
            if (dup > 0) {
                persistBank(c, key, list);
                Log.i(TAG, "题库去重：" + key + " 清掉 " + dup + " 道重复题，剩 " + list.size() + " 道");
            }
            // 已有的 AI 题登记进"出过的题"：以后被容量淘汰了也不会再被 AI 出回来
            rememberAiStems(c, key, list);
        }
        BANKS.put(key, list);
        Log.i(TAG, "题库就绪：" + key + " " + list.size() + "/" + PER_GRADE_BANK
                + " 题（内置 " + (list.size() - aiCount(list)) + " · AI " + aiCount(list) + "）");
        return list;
    }

    /** 从 assets 播种：每个科目最多取 SEED_PER_SUBJECT 道，保证三科都有题 */
    private static List<Question> seed(Context c, int grade) {
        List<Question> out = new ArrayList<Question>();
        try {
            InputStream is = c.getAssets().open("questions/grade" + grade + ".json");
            BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
            r.close();
            JSONArray arr = new JSONObject(sb.toString()).getJSONArray("questions");
            Map<String, Integer> taken = new HashMap<String, Integer>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String subject = o.getString("subject");
                Integer n = taken.get(subject);
                if (n != null && n >= SEED_PER_SUBJECT) continue;
                taken.put(subject, n == null ? 1 : n + 1);
                // id 带年级前缀：各年级的内置题 id 都是 m01/c01/e01，不加前缀会互相串（错题本指错题）
                out.add(readOne(o, "g" + grade + "_" + o.getString("id")));            }
            Log.i(TAG, "内置题库播种：g" + grade + " " + out.size() + " 题");
        } catch (Exception e) {
            // 7~9 年级没有内置题库文件，属于正常情况
            Log.w(TAG, "无内置题库：g" + grade + "（" + e.getMessage() + "）");
        }
        return out;
    }

    private static Question readOne(JSONObject o, String id) throws Exception {
        if (id == null || id.isEmpty()) {
            id = "ai_" + stableId(o.getString("subject"), o.getString("q"));
        }
        JSONArray opts = o.getJSONArray("options");
        List<String> ol = new ArrayList<String>();
        for (int j = 0; j < opts.length(); j++) {
            ol.add(opts.getString(j));
        }
        // 内置题 id 带 g 前缀、落盘时必带 src；兜底按 id 前缀归为内置题
        String src = o.optString("src", id.startsWith("g") ? Question.SRC_BUILTIN : "");
        // diff：0 = 未标注（内置题/自建题），不参与难度清洗
        return new Question(id, o.getString("subject"), o.getString("q"),
                ol, o.getInt("answer"), o.optString("explain", ""),
                src, o.optString("model", ""), o.optInt("diff", 0));
    }

    private static List<Question> parseBank(JSONArray arr) throws Exception {
        List<Question> list = new ArrayList<Question>();
        for (int i = 0; i < arr.length(); i++) {
            list.add(readOne(arr.getJSONObject(i), arr.getJSONObject(i).optString("id", "")));
        }
        return list;
    }

    private static void persistBank(Context c, String key, List<Question> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Question q : list) {
                JSONObject o = new JSONObject();
                o.put("id", q.id);
                o.put("subject", q.subject);
                o.put("q", q.q);
                JSONArray opts = new JSONArray();
                for (String s : q.options) {
                    opts.put(s);
                }
                o.put("options", opts);
                o.put("answer", q.answer);
                o.put("explain", q.explain);
                // 来源标记落盘：重启后题卡上的"🏠 本地 / 🔷 智谱 / 🔶 硅基"不会丢
                if (q.source != null && !q.source.isEmpty()) o.put("src", q.source);
                if (q.model != null && !q.model.isEmpty()) o.put("model", q.model);
                // 难度落盘：抽题时按当前难度降权，不匹配的题保留在库里
                if (q.diff > 0) o.put("diff", q.diff);
                arr.put(o);
            }
            prefs(c).edit().putString(key, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    // ---------- AI 出题入库（核心：加几道就删几道） ----------

    /**
     * AI 出题成功后调用：把题（带解析）追加进对应"年级+学期"的题库，超出容量时淘汰最旧的。
     * 年级/学期对不上（出题期间家长切了设置）整批丢弃，绝不串题库。
     */
    public static synchronized void addAiQuestions(Context c, List<Question> qs, int grade, int semester, int diff) {
        if (qs == null || qs.isEmpty()) return;
        if (grade != Store.grade(c) || semester != Store.semester(c)) {
            Log.w(TAG, "丢弃 " + bankKey(grade, semester) + " 的 AI 题（当前 "
                    + bankKey(Store.grade(c), Store.semester(c)) + "），避免串题库");
            return;
        }
        // 服务可能在 App 主界面没打开过就出题（开机自启），这里保证题库已初始化到位
        ensure(c);
        String key = bankKey(grade, semester);
        // 题干指纹：库里现有的 + 曾经出过、后来被容量淘汰的。
        // 只跟当前题库比的话，老题被 trim 淘汰后 AI 又会把它出回来，孩子看到的就是"翻来覆去同一批题"。
        List<String> seenRaw = seenStems(c, key);
        Set<String> seen = new HashSet<String>();
        for (String s : seenRaw) seen.add(normStem(s));
        int added = 0;
        List<String> fresh = new ArrayList<String>();
        for (Question q : qs) {
            String fp = normStem(q.q);
            if (fp.isEmpty()) continue;
            if (seen.contains(fp)) continue;                 // 出过的题（含已淘汰的）不再入库
            if (contains(current, q) || contains(custom, q)) continue;   // 同一道题不重复入库
            current.add(q);
            seen.add(fp);
            fresh.add(q.q);
            added++;
        }
        if (!fresh.isEmpty()) {
            seenRaw.addAll(fresh);
            saveSeenStems(c, key, seenRaw);
        }
        int removed = trim(current);
        persistBank(c, bankKey(grade, semester), current);
        Log.i(TAG, "题库更新：" + bankKey(grade, semester) + " +" + added + " 道（AI，含解析），淘汰 " + removed
                + " 道，当前 " + current.size() + "/" + PER_GRADE_BANK);
    }

    /** 判重：题干按归一化文本比（忽略空白/标点/引号差异），"12×4=?" 和 "12 × 4 = ？" 算同一道 */
    private static boolean contains(List<Question> list, Question q) {
        String fp = normStem(q.q);
        for (Question ex : list) {
            if (normStem(ex.q).equals(fp)) return true;
        }
        return false;
    }

    /** 题干归一化：去空白、去常见标点/引号、统一小写，用于判重 */
    static String normStem(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", "")
                .replaceAll("[。．.，,、；;：:！!？?\"'“”‘’「」『』（）()\\[\\]【】]", "")
                .toLowerCase(java.util.Locale.ROOT);
    }

    /** 清掉同一份题库里的重复题（保留先入库的那道），返回删掉几道 */
    private static int dedupe(List<Question> list) {
        Set<String> seen = new HashSet<String>();
        int removed = 0;
        for (int i = 0; i < list.size(); ) {
            String fp = normStem(list.get(i).q);
            if (fp.isEmpty()) {
                i++;
            } else if (seen.add(fp)) {
                i++;
            } else {
                list.remove(i);
                removed++;
            }
        }
        return removed;
    }

    /** 把库里已有的 AI 题干登记进"出过的题"历史，避免被 trim 淘汰后又被 AI 出回来 */
    private static void rememberAiStems(Context c, String key, List<Question> list) {
        List<String> seen = seenStems(c, key);
        Set<String> fp = new HashSet<String>();
        for (String s : seen) fp.add(normStem(s));
        boolean changed = false;
        for (Question q : list) {
            if (!q.isAi()) continue;
            String n = normStem(q.q);
            if (n.isEmpty() || !fp.add(n)) continue;
            seen.add(q.q);
            changed = true;
        }
        if (changed) saveSeenStems(c, key, seen);
    }

    // ---------- 已出过的题干（含已淘汰的）：防止 AI 反复出同一批题 ----------

    private static final String KEY_SEEN = "seen_";
    /** 每份题库最多记多少条历史题干（约 12KB，够覆盖好几轮的容量轮换） */
    private static final int SEEN_MAX = 150;

    private static List<String> seenStems(Context c, String key) {
        List<String> out = new ArrayList<String>();
        try {
            JSONArray arr = new JSONArray(prefs(c).getString(KEY_SEEN + key, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "").trim();
                if (!s.isEmpty()) out.add(s);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void saveSeenStems(Context c, String key, List<String> list) {
        try {
            while (list.size() > SEEN_MAX) list.remove(0);
            prefs(c).edit().putString(KEY_SEEN + key, new JSONArray(list).toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /**
     * 给 AI 出题提示用的"已经出过的题干"：最近的排前面，最多 max 条、每条截断到 40 字。
     * 目前题库 + 历史上生成过的（含被淘汰的）都算，让模型绕开这些常考题。
     */
    public static synchronized List<String> existingStems(Context c, int max) {
        List<String> raw = new ArrayList<String>(seenStems(c, currentKey));
        for (int i = current.size() - 1; i >= 0; i--) raw.add(current.get(i).q);
        for (int i = custom.size() - 1; i >= 0; i--) raw.add(custom.get(i).q);
        List<String> out = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();
        for (int i = raw.size() - 1; i >= 0 && out.size() < max; i--) {
            String s = raw.get(i);
            if (s == null) continue;
            s = s.trim();
            if (s.isEmpty()) continue;
            if (s.length() > 40) s = s.substring(0, 40) + "…";
            if (seen.add(normStem(s))) out.add(s);
        }
        return out;
    }

    /**
     * 超出容量时先淘汰最旧的 AI 题（它们可再生，重新请求一批就有），
     * AI 题删光了还超限才动内置/自建题——内置题是断网兜底，最后动。
     * 返回删掉几道。
     */
    private static int trim(List<Question> list) {
        int removed = 0;
        int i = 0;
        while (list.size() > PER_GRADE_BANK && i < list.size()) {
            if (list.get(i).isAi()) {
                list.remove(i);
                removed++;
            } else {
                i++;
            }
        }
        while (list.size() > PER_GRADE_BANK) {
            list.remove(0);
            removed++;
        }
        return removed;
    }

    private static int aiCount(List<Question> list) {
        int n = 0;
        for (Question q : list) {
            if (q.isAi()) n++;
        }
        return n;
    }

    /**
     * AI 题按来源平台分组统计，如"AI1 12 · AI2 3"（主界面只显示 AI1 / AI2，
     * 具体是哪家平台、哪个模型只写 logcat，不在界面上暴露）。
     */
    public static synchronized String aiSourceBreakdown(Context c) {
        Map<String, Integer> by = new java.util.LinkedHashMap<String, Integer>();
        for (Question q : current) {
            if (!q.isAi()) continue;
            String k = q.source == null || q.source.isEmpty() ? "旧数据" : AiQuestionClient.alias(c, q.source);
            Integer n = by.get(k);
            by.put(k, n == null ? 1 : n + 1);
        }
        if (by.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : by.entrySet()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(e.getKey()).append(" ").append(e.getValue());
        }
        return sb.toString();
    }

    /** 当前年级题库里 AI 出的题数 */
    public static synchronized int aiCount() {
        return aiCount(current);
    }

    /** 当前年级题库里内置题数 */
    public static synchronized int builtinCount() {
        return current.size() - aiCount(current);
    }

    /** 答错的题不需要额外回收：题库本身就持久化，重启后 findById 仍能找到 */
    public static synchronized void retainQuestion(Context c, Question q) {
        if (q == null) return;
        ensure(c);
        if (contains(current, q)) return;
        current.add(q);
        int removed = trim(current);
        persistBank(c, bankKey(Store.grade(c), Store.semester(c)), current);
        Log.i(TAG, "错题回填题库：" + q.id + "（淘汰 " + removed + " 道）");
    }

    // ---------- 题库缓存：只留最近 8 份（年级×学期），久不用的自动清 ----------

    /**
     * 记录题库使用情况并整理缓存：
     *  1. 当前题库记为"最近使用"，时间戳用于判断"多久没被选过"；
     *  2. 只保留最近 MAX_CACHED_BANKS 份，超出的整套清掉；
     *  3. 其余里超过 CACHE_IDLE_DAYS 天没被选过的也清掉；
     *  4. 老版本只按年级存的题库（键里没有 _s）直接清掉，学期版会重新播种。
     */
    private static void touchBank(Context c, String key) {
        SharedPreferences meta = c.getSharedPreferences(META_FILE, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        try {
            JSONArray orderArr = new JSONArray(meta.getString("order", "[]"));
            List<String> keys = new ArrayList<String>();
            keys.add(key);
            for (int i = 0; i < orderArr.length(); i++) {
                String v = orderArr.optString(i);
                if (!v.isEmpty() && !v.equals(key)) keys.add(v);
            }
            // 磁盘上有题库但不在 order 里的（历史遗留），也交给下面的规则处理
            for (String k : prefs(c).getAll().keySet()) {
                if (k.startsWith(KEY_BANK) && !keys.contains(k)) keys.add(k);
            }

            List<String> keep = new ArrayList<String>();
            keep.add(key);
            for (int i = 1; i < keys.size(); i++) {
                String k = keys.get(i);
                long last = meta.getLong("used_" + k, 0L);
                long idleDays = last <= 0 ? -1 : (now - last) / 86400000L;
                if (keep.size() >= MAX_CACHED_BANKS) {
                    dropBank(c, meta, k, "超出最近 " + MAX_CACHED_BANKS + " 份题库");
                } else if (idleDays >= CACHE_IDLE_DAYS) {
                    dropBank(c, meta, k, "已 " + idleDays + " 天未被选择");
                } else {
                    if (last <= 0) meta.edit().putLong("used_" + k, now).apply();
                    keep.add(k);
                }
            }

            JSONArray out = new JSONArray();
            for (String k : keep) out.put(k);
            meta.edit().putString("order", out.toString()).putLong("used_" + key, now).apply();

            // 再扫一遍磁盘：不在 keep 里的题库（含老格式残留）一并清掉
            SharedPreferences bp = prefs(c);
            SharedPreferences.Editor be = bp.edit();
            boolean changed = false;
            for (String k : bp.getAll().keySet()) {
                if (!k.startsWith(KEY_BANK)) continue;
                boolean legacy = !k.contains("_s");   // 老版本只按年级存的题库
                if (!legacy && keep.contains(k)) continue;
                be.remove(k);
                BANKS.remove(k);
                changed = true;
                Log.i(TAG, "清理题库 " + k + (legacy ? "（老格式，学期版会重新播种）"
                        : "（不在最近 " + MAX_CACHED_BANKS + " 份内）"));
            }
            if (changed) be.apply();
        } catch (Exception e) {
            Log.w(TAG, "题库缓存整理异常", e);
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    /** 清掉某份题库 + 使用时间戳 */
    private static void dropBank(Context c, SharedPreferences meta, String key, String reason) {
        meta.edit().remove("used_" + key).apply();
        prefs(c).edit().remove(key).apply();
        BANKS.remove(key);
        if (key.equals(currentKey)) {
            current = new ArrayList<Question>();
            currentKey = "";
        }
        Log.i(TAG, "清理题库 " + key + "：" + reason);
    }

    // ---------- 家长自建题（局域网面板导入 / 删除） ----------

    private static void loadCustom(Context c) {
        custom.clear();
        try {
            String raw = prefs(c).getString(KEY_CUSTOM, null);
            if (raw == null) return;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                JSONArray opts = o.getJSONArray("options");
                List<String> ol = new ArrayList<String>();
                for (int j = 0; j < opts.length(); j++) {
                    ol.add(opts.getString(j));
                }
                custom.add(new Question(
                        o.optString("id", "c_" + stableId(o.getString("subject"), o.getString("q"))),
                        o.getString("subject"), o.getString("q"),
                        ol, o.getInt("answer"), o.optString("explain", ""),
                        Question.SRC_CUSTOM, ""));
            }
        } catch (Exception ignored) {
        }
    }

    private static void persistCustom(Context c) {
        try {
            JSONArray arr = new JSONArray();
            for (Question q : custom) {
                JSONObject o = new JSONObject();
                o.put("id", q.id);
                o.put("subject", q.subject);
                o.put("q", q.q);
                JSONArray opts = new JSONArray();
                for (String s : q.options) {
                    opts.put(s);
                }
                o.put("options", opts);
                o.put("answer", q.answer);
                o.put("explain", q.explain);
                o.put("src", Question.SRC_CUSTOM);
                arr.put(o);
            }
            prefs(c).edit().putString(KEY_CUSTOM, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /**
     * 批量导入自建题（调用方需先校验格式）。同 id 视为覆盖，避免重复导入。
     * @return 实际新增（不含覆盖）的题数
     */
    public static synchronized int addCustom(Context c, List<Question> qs) {
        int added = 0;
        for (Question q : qs) {
            int existing = -1;
            for (int i = 0; i < custom.size(); i++) {
                if (custom.get(i).id.equals(q.id)) {
                    existing = i;
                    break;
                }
            }
            if (existing >= 0) {
                custom.set(existing, q);
            } else {
                custom.add(q);
                added++;
            }
        }
        persistCustom(c);
        Log.i(TAG, "自建题导入：" + added + " 题，当前共 " + custom.size() + " 题");
        return added;
    }

    /** 删除自建题（题库题/AI 题不可删） */
    public static synchronized boolean removeCustom(Context c, String id) {
        for (int i = 0; i < custom.size(); i++) {
            if (custom.get(i).id.equals(id)) {
                custom.remove(i);
                persistCustom(c);
                return true;
            }
        }
        return false;
    }

    public static synchronized int customCount() {
        return custom.size();
    }

    /** 管理面板用：当前年级题库 + 自建题的全量快照 */
    public static synchronized List<Question> snapshot() {
        List<Question> list = new ArrayList<Question>(current);
        list.addAll(custom);
        return list;
    }

    /** 该题是否为家长自建题（自建题才允许删除） */
    public static synchronized boolean isCustom(String id) {
        for (Question q : custom) {
            if (q.id.equals(id)) return true;
        }
        return false;
    }

    /** 当前年级题库题数（含内置 + AI） */
    public static synchronized int size() {
        return current.size();
    }

    /**
     * 按 id 查题：当前题库 + 已加载的其它题库 + 自建题。
     * 内置题 id 带年级前缀（g5_m01），找不到时把那个年级（按当前学期）的题库载进来再查一次。
     */
    public static synchronized Question findById(Context c, String id) {
        Question q = findInLoaded(id);
        if (q != null) return q;
        if (id != null && id.startsWith("g") && id.indexOf('_') > 1) {
            int g = parseInt(id.substring(1, id.indexOf('_')), -1);
            if (g >= 1 && g <= 9) {
                List<Question> bank = loadBank(c, bankKey(g, Store.semester(c)), g);
                for (Question x : bank) {
                    if (x.id.equals(id)) return x;
                }
            }
        }
        return null;
    }

    private static Question findInLoaded(String id) {
        for (Question q : current) {
            if (q.id.equals(id)) return q;
        }
        for (List<Question> bank : BANKS.values()) {
            for (Question q : bank) {
                if (q.id.equals(id)) return q;
            }
        }
        for (Question q : custom) {
            if (q.id.equals(id)) return q;
        }
        return null;
    }

    /** 一键重置：把所有题库恢复成内置基线（AI 出的题清掉），下次启动会重新补充 */
    public static synchronized void clearAiCache(Context c) {
        SharedPreferences p = prefs(c);
        SharedPreferences.Editor e = p.edit();
        for (String k : p.getAll().keySet()) {
            // 连同"已出过的题干"历史一起清：重置后允许 AI 重新出这些题
            if (k.startsWith(KEY_BANK) || k.startsWith(KEY_SEEN)) e.remove(k);
        }
        e.apply();
        BANKS.clear();
        currentKey = "";
        syncGrade(c);
        // 清掉"每日已更新"标记，下次启动会重新生成当天缓存
        Store.clearAiCacheDays(c);
        Log.i(TAG, "题库已重置为内置基线：" + bankKey(Store.grade(c), Store.semester(c))
                + " " + current.size() + " 题");
    }

    public static String subjectName(String subject) {
        if ("math".equals(subject)) return "数学";
        if ("chinese".equals(subject)) return "语文";
        if ("english".equals(subject)) return "英语";
        return "综合";
    }

    // ---------- 抽题 ----------

    /** 抽一道题。加锁：AI 线程随时可能往题库里追加/淘汰题目 */
    public static synchronized Question randomQuestion(Context c) {
        ensure(c);

        List<Question> base = new ArrayList<Question>(current);   // 当前年级题库（内置 + AI）
        base.addAll(custom);                                      // 家长自建题同等参与
        if (base.isEmpty()) {
            // 题库还没建起来（AI 刚失败、内置题也没播种成功）：绝不拿别的年级题糊弄孩子。
            // 初一/初二/初三统一先用初一的题库兜底，同时立刻补一次 AI 出题。
            int fbGrade = fallbackGrade(Store.grade(c));
            List<Question> fb = fbGrade == Store.grade(c)
                    ? new ArrayList<Question>()
                    : loadBank(c, bankKey(fbGrade, Store.semester(c)), fbGrade);
            AiQuestionClient.prefetchAsync(c);
            if (!fb.isEmpty()) {
                Log.w(TAG, "g" + Store.grade(c) + " 暂无可用的题，临时用 g" + fbGrade + " 题库兜底");
                base.addAll(fb);
            } else {
                return fallbackQuestion();
            }
        }

        // 错题优先复现（25% 概率）
        Map<String, Integer> wrong = Store.wrongBook(c);
        if (!wrong.isEmpty() && RND.nextInt(100) < 25) {
            List<String> keys = new ArrayList<String>(wrong.keySet());
            String id = keys.get(RND.nextInt(keys.size()));
            for (Question q : base) {
                if (q.id.equals(id)) return q;
            }
        }

        Set<String> recent = Store.recentIds(c);
        // 难度匹配优先：AI 题带出题时的难度标记（内置/自建题 diff=0 视为通用）。
        // 当前难度有足够多的匹配题时只从匹配集里抽，其他难度的 AI 题留在库里
        // 不被抽到（切回那个难度立即可用，不用重新请求）；匹配题太少（刚切过来、
        // AI 还没出出来）就整库抽，保证永远有题可出，不会把题库"切空"。
        int diff = Store.difficulty(c);
        List<Question> pool = base;
        int matched = 0;
        for (Question q : base) {
            if (q.diff <= 0 || q.diff == diff) matched++;
        }
        if (matched >= 10 && matched < base.size()) {
            pool = new ArrayList<Question>();
            for (Question q : base) {
                if (q.diff <= 0 || q.diff == diff) pool.add(q);
            }
        }
        Question q = pool.get(RND.nextInt(pool.size()));
        int tries = 0;
        while (recent.contains(q.id) && tries < 12) {
            q = pool.get(RND.nextInt(pool.size()));
            tries++;
        }
        Store.addRecentId(c, q.id);
        return q;
    }

    /** 当前年级真没题时用哪份题库兜底：初中统一用初一（难度最接近），小学用五年级 */
    private static int fallbackGrade(int grade) {
        return grade >= 7 ? 7 : 5;
    }

    private static Question fallbackQuestion() {
        List<String> opts = new ArrayList<String>();
        opts.add("2");
        opts.add("3");
        return new Question("fallback", "math", "1 + 1 = ?", opts, 0, "1+1=2",
                Question.SRC_BUILTIN, "");
    }
}
