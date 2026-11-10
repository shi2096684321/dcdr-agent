package com.dcdr.agent.agent.memory.strategy;

/**
 * 一次摘要生成的结果(V3.5)。
 *
 * <p><b>为什么不再只返回 String</b>: 压缩事件要落库,(模型名 / 耗时 / 失败原因)必须一并带出来 ——
 * 否则"这次压缩花了多久"只能去文件日志里捞, 而失败原因(超时 or 空返回 or 配置写错)
 * 恰恰是降级记录里最需要解释的一栏。文本与元信息同时返回, 也避免调用方再维护一个
 * "上一次调用的状态"字段那样的隐式状态。
 *
 * @param text          摘要正文; 失败时为 {@code null}
 * @param model         实际使用的 provider 名; 组装阶段就失败时为 {@code null}
 * @param costMs        本次调用耗时(毫秒); 未真正发起调用时为 0
 * @param failureReason 失败原因(给事件记录与用户看的一句话); 成功时为 {@code null}
 */
public record SummaryResult(String text, String model, long costMs, String failureReason) {

    public static SummaryResult ok(String text, String model, long costMs) {
        return new SummaryResult(text, model, costMs, null);
    }

    public static SummaryResult failed(String reason, long costMs) {
        return new SummaryResult(null, null, costMs, reason == null || reason.isBlank() ? "摘要生成失败" : reason);
    }

    /** 成功与否只看正文, 与旧接口"返回 null 表示失败"保持同一口径 */
    public boolean ok() {
        return text != null && !text.isBlank();
    }
}
