package com.family.quizlock;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 所有持久化数据（配置 / 观看计时 / 错题本 / 答题统计）。
 * 全部存 SharedPreferences，重启不丢 —— 防止"断电重启清零计时"。
 */
public final class Store {
    private static final String FILE = "quizlock_prefs";

    private Store() {
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---------- 配置 ----------

    public static int intervalMinutes(Context c) {
        return p(c).getInt("interval_minutes", 10);
    }

    public static void setIntervalMinutes(Context c, int v) {
        p(c).edit().putInt("interval_minutes", clamp(v, 5, 30)).apply();
    }

    public static int requiredCorrect(Context c) {
        return p(c).getInt("required_correct", 5);
    }

    public static void setRequiredCorrect(Context c, int v) {
        // 最少 1 题：只想让孩子答一道题就放行时也能设
        p(c).edit().putInt("required_correct", clamp(v, 1, 10)).apply();
    }

    // ---------- 观看计时（累计屏幕亮着的秒数，重启不清零） ----------

    public static long watchedSeconds(Context c) {
        return p(c).getLong("watched_seconds", 0L);
    }

    public static void setWatchedSeconds(Context c, long v) {
        p(c).edit().putLong("watched_seconds", v).apply();
    }

    // ---------- 开机即弹题 ----------

    public static boolean bootQuizPending(Context c) {
        return p(c).getBoolean("boot_quiz_pending", false);
    }

    public static void setBootQuizPending(Context c, boolean v) {
        p(c).edit().putBoolean("boot_quiz_pending", v).apply();
    }

    // ---------- 最近出过的题（避免短时间重复，最多记 15 条） ----------

    public static Set<String> recentIds(Context c) {
        Set<String> s = p(c).getStringSet("recent_ids", null);
        return s == null ? new HashSet<String>() : s;
    }

    public static void addRecentId(Context c, String id) {
        Set<String> s = new LinkedHashSet<String>(recentIds(c));
        s.add(id);
        while (s.size() > 15) {
            s.remove(s.iterator().next());
        }
        p(c).edit().putStringSet("recent_ids", new HashSet<String>(s)).apply();
    }

    // ---------- 错题本：id -> 错误次数 ----------

    public static Map<String, Integer> wrongBook(Context c) {
        Map<String, Integer> map = new HashMap<String, Integer>();
        String raw = p(c).getString("wrong_book", null);
        if (raw == null) return map;
        try {
            JSONObject o = new JSONObject(raw);
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                map.put(k, o.getInt(k));
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    public static void recordWrong(Context c, String id) {
        recordWrongToday(c);
        Map<String, Integer> map = new HashMap<String, Integer>(wrongBook(c));
        Integer old = map.get(id);
        map.put(id, old == null ? 1 : old + 1);
        // 错题本最多 50 条，超出时移除错得最少的（已基本掌握）
        while (map.size() > 50) {
            String minKey = null;
            int min = Integer.MAX_VALUE;
            for (Map.Entry<String, Integer> e : map.entrySet()) {
                if (e.getValue() < min) {
                    min = e.getValue();
                    minKey = e.getKey();
                }
            }
            map.remove(minKey);
        }
        p(c).edit().putString("wrong_book", new JSONObject(map).toString()).apply();
    }

    /**
     * 答错时同时存一份题目快照。
     *
     * <p>错题本只记 id，靠 {@code QuestionBank.findById} 反查题目。可选年级只有两个缓存位，
     * 加上题库 30 题滚动淘汰，题目很可能在家长点开错题本之前就被清掉了——那时错题本里只剩
     * "找不到这道题"。存快照后，题目出了题库照样能把题干/答案/解析摆出来。
     */
    public static void recordWrong(Context c, Question q) {
        if (q == null) return;
        recordWrong(c, q.id);
        saveWrongDetail(c, q);
    }

    private static void saveWrongDetail(Context c, Question q) {
        try {
            JSONObject all = wrongDetails(c);
            JSONObject o = new JSONObject();
            o.put("subject", q.subject);
            o.put("q", q.q);
            o.put("answer", q.answer >= 0 && q.answer < q.options.size()
                    ? q.options.get(q.answer) : "");
            o.put("explain", q.explain == null ? "" : q.explain);
            all.put(q.id, o);
            // 与错题本对齐：错题本里已经没有的 id 不再保留快照，条数上限 60
            Map<String, Integer> wrong = wrongBook(c);
            Iterator<String> it = all.keys();
            java.util.List<String> drop = new java.util.ArrayList<String>();
            while (it.hasNext()) {
                String k = it.next();
                if (!wrong.containsKey(k)) drop.add(k);
            }
            for (String k : drop) all.remove(k);
            p(c).edit().putString("wrong_detail", all.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static JSONObject wrongDetails(Context c) {
        try {
            String raw = p(c).getString("wrong_detail", "");
            if (!raw.isEmpty()) return new JSONObject(raw);
        } catch (Exception ignored) {
        }
        return new JSONObject();
    }

    /** 某道错题的快照（题目已被题库淘汰时用），没有则返回 null */
    public static JSONObject wrongDetail(Context c, String id) {
        try {
            JSONObject all = wrongDetails(c);
            return all.has(id) ? all.getJSONObject(id) : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static void clearWrongBook(Context c) {
        p(c).edit().remove("wrong_book").remove("wrong_detail").apply();
    }

    /** 从错题本移除单题（家长在手机上判断孩子已掌握） */
    public static boolean removeWrong(Context c, String id) {
        Map<String, Integer> map = new HashMap<String, Integer>(wrongBook(c));
        if (map.remove(id) == null) return false;
        JSONObject detail = wrongDetails(c);
        detail.remove(id);
        p(c).edit().putString("wrong_book", new JSONObject(map).toString())
                .putString("wrong_detail", detail.toString()).apply();
        return true;
    }

    /** 错题本中指定题目的错误次数 */
    public static int wrongCount(Context c, String id) {
        Integer n = wrongBook(c).get(id);
        return n == null ? 0 : n;
    }

    // ---------- 今日答错统计（跨天自动重置） ----------

    private static String today() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date());
    }

    public static int wrongToday(Context c) {
        SharedPreferences p = p(c);
        if (!today().equals(p.getString("wrong_today_day", ""))) return 0;
        return p.getInt("wrong_today_count", 0);
    }

    private static void recordWrongToday(Context c) {
        SharedPreferences p = p(c);
        String day = today();
        int count = day.equals(p.getString("wrong_today_day", ""))
                ? p.getInt("wrong_today_count", 0) : 0;
        p.edit().putString("wrong_today_day", day)
                .putInt("wrong_today_count", count + 1).apply();
    }

    // ---------- 答题统计 ----------

    public static int statTotal(Context c) {
        return p(c).getInt("stat_total", 0);
    }

    public static int statCorrect(Context c) {
        return p(c).getInt("stat_correct", 0);
    }

    public static void addStat(Context c, boolean correct) {
        p(c).edit()
                .putInt("stat_total", statTotal(c) + 1)
                .putInt("stat_correct", statCorrect(c) + (correct ? 1 : 0))
                .apply();
    }

    // ---------- 总开关（家长密码控制） ----------

    public static boolean enabled(Context c) {
        return p(c).getBoolean("enabled", true);
    }

    public static void setEnabled(Context c, boolean v) {
        p(c).edit().putBoolean("enabled", v).apply();
    }

    public static boolean hasPassword(Context c) {
        return p(c).contains("parent_password");
    }

    /** 家长密码：首次使用由 App 引导设置，无内置默认值（避免被孩子猜中） */
    public static String password(Context c) {
        return p(c).getString("parent_password", "");
    }

    public static void setPassword(Context c, String v) {
        p(c).edit().putString("parent_password", v == null ? "" : v.trim()).apply();
    }

    /** 学期：1=上学期，2=下学期（全局设置，题库/AI 出题都按它分开） */
    public static int semester(Context c) {
        return p(c).getInt("semester", 1);
    }

    public static void setSemester(Context c, int v) {
        p(c).edit().putInt("semester", clamp(v, 1, 2)).apply();
    }

    public static String semesterName(int v) {
        return v >= 2 ? "下学期" : "上学期";
    }

    // ---------- AI 离线缓存每日更新标记（按 年级+学期 分开记，换年级/学期当天也会重新生成） ----------

    public static String aiCacheDay(Context c, int grade, int semester) {
        return p(c).getString("ai_cache_day_g" + grade + "_s" + semester, "");
    }

    public static void setAiCacheDay(Context c, int grade, int semester, String day) {
        p(c).edit().putString("ai_cache_day_g" + grade + "_s" + semester, day).apply();
    }

    /** 清空所有年级的"今日已更新"标记（清缓存时用） */
    public static void clearAiCacheDays(Context c) {
        SharedPreferences sp = p(c);
        SharedPreferences.Editor e = sp.edit();
        for (String k : sp.getAll().keySet()) {
            if (k.startsWith("ai_cache_day")) e.remove(k);
        }
        e.apply();
    }

    // ---------- AI 出题状态（主界面显示最近一次失败原因，便于区分网络/限流/配置问题） ----------

    public static String aiLastError(Context c) {
        return p(c).getString("ai_last_error", "");
    }

    public static void setAiLastError(Context c, String msg) {
        p(c).edit().putString("ai_last_error", msg == null ? "" : msg).apply();
    }

    /** 最近一次成功出题的"平台 · 模型"，如"硅基流动 · Qwen/Qwen3-8B"（主界面展示真实出处） */
    public static String aiLastOk(Context c) {
        return p(c).getString("ai_last_ok", "");
    }

    public static void setAiLastOk(Context c, String v) {
        p(c).edit().putString("ai_last_ok", v == null ? "" : v).apply();
    }

    // ---------- 年级（1~9，AI 出题按此适配难度；默认值来自 gradle.properties） ----------
    public static int grade(Context c) {
        return p(c).getInt("grade", BuildConfig.DEFAULT_GRADE);
    }

    public static void setGrade(Context c, int v) {
        p(c).edit().putInt("grade", clamp(v, 1, 9)).apply();
    }

    // ---------- 题目难度（按年级各存一份：1=易 2=中 3=难） ----------

    public static int difficulty(Context c) {
        return p(c).getInt("difficulty_g" + grade(c), 2);
    }

    public static void setDifficulty(Context c, int v) {
        p(c).edit().putInt("difficulty_g" + grade(c), clamp(v, 1, 3)).apply();
    }

    public static String difficultyName(int v) {
        if (v <= 1) return "简单";
        if (v >= 3) return "较难";
        return "中等";
    }

    // ---------- AI 出题配置（默认值打包自 secrets.properties，运行期可被 adb 覆盖） ----------

    public static String aiKey(Context c) {
        return p(c).getString("ai_key", Secret.aiKey());
    }

    public static void setAiKey(Context c, String v) {
        p(c).edit().putString("ai_key", v == null ? "" : v.trim()).apply();
    }

    public static String aiBaseUrl(Context c) {
        return p(c).getString("ai_base_url", BuildConfig.AI_BASE_URL);
    }

    public static void setAiBaseUrl(Context c, String v) {
        p(c).edit().putString("ai_base_url", v == null ? "" : v.trim()).apply();
    }

    public static String aiModel(Context c) {
        return p(c).getString("ai_model", BuildConfig.AI_MODEL);
    }

    public static void setAiModel(Context c, String v) {
        p(c).edit().putString("ai_model", v == null ? "" : v.trim()).apply();
    }

    // ---------- 备用 AI 平台（默认硅基流动；key 为空表示未启用） ----------
    // 主平台整体过载（1305）时所有模型会一起被拒，降级链全部失效；
    // 配了备用 key 后，会在主平台候选模型全部失败后再打备用平台。

    public static String aiKey2(Context c) {
        return p(c).getString("ai_key2", Secret.aiKey2());
    }

    public static void setAiKey2(Context c, String v) {
        p(c).edit().putString("ai_key2", v == null ? "" : v.trim()).apply();
    }

    public static String aiBaseUrl2(Context c) {
        return p(c).getString("ai_base_url2", BuildConfig.AI_BASE_URL_2);
    }

    public static void setAiBaseUrl2(Context c, String v) {
        p(c).edit().putString("ai_base_url2", v == null ? "" : v.trim()).apply();
    }

    public static String aiModel2(Context c) {
        return p(c).getString("ai_model2", BuildConfig.AI_MODEL_2);
    }

    public static void setAiModel2(Context c, String v) {
        p(c).edit().putString("ai_model2", v == null ? "" : v.trim()).apply();
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
