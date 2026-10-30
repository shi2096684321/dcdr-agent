package com.dcdr.agent.notify;

import lombok.Data;

/**
 * 单次推送结果。
 * <p>
 * 区分 {@code skipped} 与 {@code failed} 很重要: 前者是"按规则没发"(节流/总开关关闭),
 * 属预期行为; 后者是"发了但没成功", 需要用户介入。若都记成 failed,
 * 节流会持续制造假告警。
 */
@Data
public class NotifyResult {

    private boolean success;
    private boolean skipped;
    /** 实际/期望使用的渠道 code */
    private String channel;
    /** 失败原因 (success=false 且 skipped=false 时有值) */
    private String error;
    /** 跳过原因 (skipped=true 时有值) */
    private String reason;

    public static NotifyResult success(String channel) {
        NotifyResult r = new NotifyResult();
        r.setSuccess(true);
        r.setChannel(channel);
        return r;
    }

    public static NotifyResult failed(String channel, String error) {
        NotifyResult r = new NotifyResult();
        r.setSuccess(false);
        r.setChannel(channel);
        r.setError(error);
        return r;
    }

    public static NotifyResult skipped(String channel, String reason) {
        NotifyResult r = new NotifyResult();
        r.setSkipped(true);
        r.setChannel(channel);
        r.setReason(reason);
        return r;
    }

    /** 落库展示用的状态字符串 */
    public String statusText() {
        if (success) return "success";
        if (skipped) return "skipped";
        return "failed";
    }

    /** 落库展示用的原因 (失败原因或跳过原因) */
    public String detail() {
        return error != null ? error : reason;
    }
}
