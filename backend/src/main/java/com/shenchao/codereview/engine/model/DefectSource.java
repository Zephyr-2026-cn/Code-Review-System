package com.shenchao.codereview.engine.model;

/** 缺陷来源。融合后的结论记为 {@link #FUSED}，用于统计"双引擎一致发现了多少"。 */
public enum DefectSource {

    STATIC("静态分析"),
    LLM("大模型"),
    FUSED("双引擎融合");

    private final String label;

    DefectSource(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
