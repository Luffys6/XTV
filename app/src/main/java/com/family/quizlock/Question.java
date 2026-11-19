package com.family.quizlock;

import java.util.List;

/**
 * 一道选择题。options 为选项文本，answer 为正确选项下标（渲染时会打乱顺序）。
 *
 * <p>{@link #source} / {@link #model} 记录这道题的"真实出身"：本地内置、家长自建，
 * 还是哪个平台（智谱 / 硅基流动）的哪个模型出的。题卡上会把它显示出来，
 * 家长一眼能看出孩子做的题是本地题库还是 AI 现出的、由谁出的。
 *
 * <p>{@link #diff} 记录 AI 出这道题时用的难度（1 易 / 2 中 / 3 难），0 表示未标注
 * （内置题、家长自建题）。家长把难度调低后，旧难度的 AI 题会被
 * 清出题库，否则"调到简单"了孩子还在做难题。
 */
public class Question {
    /** 来源：内置题库 */
    public static final String SRC_BUILTIN = "builtin";
    /** 来源：家长自建题 */
    public static final String SRC_CUSTOM = "custom";

    public final String id;
    public final String subject;   // math / chinese / english
    public final String q;
    public final List<String> options;
    public final int answer;
    public final String explain;
    /** 来源平台名（"builtin" / "custom" / "智谱" / "硅基流动" …），老数据为空 */
    public final String source;
    /** 生成该题的模型 id（如 glm-4.5-flash、Qwen/Qwen3-8B），本地题为空 */
    public final String model;
    /** 出题难度：1 易 / 2 中 / 3 难；0 = 未标注（内置 / 自建 / 老数据） */
    public final int diff;

    public Question(String id, String subject, String q, List<String> options, int answer, String explain) {
        this(id, subject, q, options, answer, explain, "", "", 0);
    }

    public Question(String id, String subject, String q, List<String> options, int answer,
                    String explain, String source, String model) {
        this(id, subject, q, options, answer, explain, source, model, 0);
    }

    public Question(String id, String subject, String q, List<String> options, int answer,
                    String explain, String source, String model, int diff) {
        this.id = id;
        this.subject = subject;
        this.q = q;
        this.options = options;
        this.answer = answer;
        this.explain = explain;
        this.source = source == null ? "" : source;
        this.model = model == null ? "" : model;
        this.diff = diff;
    }

    /** 补上难度标记（AI 出题入库前统一打标，其余字段不变） */
    public Question withDiff(int d) {
        if (d == diff) return this;
        return new Question(id, subject, q, options, answer, explain, source, model, d);
    }

    /** 是否本地题（内置 / 自建 / 老数据里没有来源标记的 AI 题除外） */
    public boolean isLocal() {
        return SRC_BUILTIN.equals(source) || SRC_CUSTOM.equals(source);
    }

    /** 是否 AI 出的题（新数据看 source，老数据看 id 前缀） */
    public boolean isAi() {
        if (!source.isEmpty()) return !isLocal();
        return id != null && (id.startsWith("ai_") || id.startsWith("cache_"));
    }
}
