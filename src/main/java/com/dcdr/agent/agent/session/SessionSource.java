package com.dcdr.agent.agent.session;

import com.dcdr.agent.common.exception.BusinessException;

import java.util.List;

/**
 * 会话来源: 区隔"用户自己开的对话"与"系统自动执行产生的对话"。
 * <p>
 * 为什么需要它: 定时任务与工作流每执行一次都会建一个临时会话跑 Agent。这些会话过去要么
 * 执行完被删掉(失败则残留), 要么混在对话列表里 —— 用户既分不清哪些是自己聊的,
 * 也没地方回看"那次定时任务到底问了什么、答了什么"。落一个显式来源字段后:
 * <ul>
 *   <li>对话侧边栏只查 {@link #USER}, 列表回到"我开的会话";</li>
 *   <li>自动化下的「任务对话」查 {@link #SCHEDULE} + {@link #WORKFLOW}, 可回看执行过程;</li>
 *   <li>保留数量按 {@code sourceRef}(任务/工作流 ID)分别收敛, 不会无限膨胀。</li>
 * </ul>
 * 刻意不用"标题前缀是不是 schedule:"来判断: 标题会被首个用户问题自动改写, 前缀留不住。
 */
public enum SessionSource {

    /** 用户在对话页自己创建的会话 */
    USER("user"),
    /** 定时任务到点触发时自动创建的会话 */
    SCHEDULE("schedule"),
    /** 工作流步骤执行时自动创建的会话 */
    WORKFLOW("workflow");

    private final String code;

    SessionSource(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /**
     * 中文名, 供界面直接展示。
     * <p><b>为什么放在这里而不是前端</b>: 与 {@code WebStat.Empty} 同一口径 ——
     * 枚举的中文名一旦前后端各写一份, 加一个取值时必然漏一边, 界面上就留下一个没人认得的英文单词。
     */
    public String label() {
        return switch (this) {
            case USER -> "用户对话";
            case SCHEDULE -> "定时任务";
            case WORKFLOW -> "工作流";
        };
    }

    /**
     * 把库里的 code 还原成中文名; 未知值与历史空值一律按"用户对话"处理。
     * <p>历史数据没有 source 列, 而那时只有用户对话会写日志 —— 兜底成 {@link #USER} 是事实, 不是猜。
     */
    public static String labelOf(String code) {
        if (code == null || code.isBlank()) return USER.label();
        for (SessionSource s : values()) {
            if (s.code.equalsIgnoreCase(code)) return s.label();
        }
        return code;
    }

    /**
     * 把接口入参解析成待查询的来源清单。
     * <p>
     * 默认(不传)只返回 {@link #USER} —— 这是"对话列表该长什么样"的产品默认值,
     * 而不是"返回全部再由前端过滤": 过滤放在服务端, 自动执行产生的会话才不会漏进对话栏。
     *
     * @param raw 接口入参: 空 / user / schedule / workflow / automation / all
     * @return 至少含一个来源的清单
     */
    public static List<String> parseFilter(String raw) {
        if (raw == null || raw.isBlank()) return List.of(USER.code);
        return switch (raw.trim().toLowerCase()) {
            case "user" -> List.of(USER.code);
            case "schedule" -> List.of(SCHEDULE.code);
            case "workflow" -> List.of(WORKFLOW.code);
            // 自动化页签的语义: "系统自动跑出来的会话", 定时任务与工作流都算
            case "automation" -> List.of(SCHEDULE.code, WORKFLOW.code);
            case "all" -> List.of(USER.code, SCHEDULE.code, WORKFLOW.code);
            default -> throw new BusinessException(400, "无法识别的会话来源: " + raw);
        };
    }
}
