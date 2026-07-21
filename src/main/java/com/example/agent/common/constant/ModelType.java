package com.example.agent.common.constant;

/**
 * 模型类型枚举
 */
public enum ModelType {

    /** 对话模型 */
    CHAT("对话"),
    /** 图片生成模型 */
    IMAGE("图片"),
    /** 视频生成模型 */
    VIDEO("视频"),
    /** 语音模型 */
    AUDIO("语音");

    private final String label;

    ModelType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
