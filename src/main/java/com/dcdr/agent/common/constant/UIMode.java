package com.dcdr.agent.common.constant;

/**
 * UI模式枚举 - 决定前端渲染哪种界面
 */
public enum UIMode {

    /** 对话模式: 线性聊天界面(类ChatGPT) */
    CHAT("chat"),
    /** 创作模式: 左控右显界面(类Banana, 用于图片/视频生成) */
    CREATIVE("creative");

    private final String value;

    UIMode(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * 根据模型类型推断UI模式
     */
    public static UIMode fromModelType(ModelType type) {
        if (type == ModelType.IMAGE || type == ModelType.VIDEO) {
            return CREATIVE;
        }
        return CHAT;
    }
}
