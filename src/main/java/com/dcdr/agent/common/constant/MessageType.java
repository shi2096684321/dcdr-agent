package com.dcdr.agent.common.constant;

/**
 * 消息类型枚举
 */
public enum MessageType {

    /** 文本消息 */
    TEXT("text"),
    /** 图片消息 */
    IMAGE("image"),
    /** 视频消息 */
    VIDEO("video"),
    /** 工具调用 */
    TOOL_CALL("tool_call"),
    /** 工具结果 */
    TOOL_RESULT("tool_result");

    private final String value;

    MessageType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
