package com.dcdr.agent.workspace;

/**
 * 工作区授权档位(V3.3 F-49 §4)。
 * <p>
 * <b>读授权不等于删授权</b> —— 这是本设计的核心约束。给了 READ 只代表能看,
 * 想改要升 WRITE, 想删还要再升 FULL。档位由用户在界面上显式选择, 默认 {@link #READ},
 * 模型任何参数都无法影响它。
 */
public enum WorkspaceAccess {

    /** 只读: list / read / search */
    READ,

    /** 可写: 上述 + write / edit */
    WRITE,

    /** 可写可删: 上述 + delete */
    FULL;

    /** 该档位是否覆盖指定意图 */
    public boolean allows(WorkspaceIntent intent) {
        return switch (intent) {
            case READ -> true;
            case WRITE -> this == WRITE || this == FULL;
            case DELETE -> this == FULL;
        };
    }

    /** 解析档位; 无法识别时返回 null, 由调用方给出"档位非法"的明确报错 */
    public static WorkspaceAccess parse(String raw) {
        if (raw == null || raw.isBlank()) return READ;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 给模型看的中文名(注入 system prompt 的工作区摘要用) */
    public String label() {
        return switch (this) {
            case READ -> "只读";
            case WRITE -> "可写";
            case FULL -> "可写可删";
        };
    }

    /**
     * 该档位可用的工具清单, 供 system prompt 摘要点名。
     * <p>
     * 为什么要按档位列出而不是笼统写"用 workspace_* 工具": 工具注册本身已按档位过滤,
     * 模型若在提示里看到未注册的名字, 会反复尝试调用一个不存在的工具(报错、重试、浪费轮次)。
     * 点名可用的那几个, 它就不会去猜。
     */
    public String tools() {
        return switch (this) {
            case READ -> "`workspace_list`、`workspace_read`、`workspace_search`";
            case WRITE -> "`workspace_list`、`workspace_read`、`workspace_search`、"
                    + "`workspace_write`、`workspace_edit`";
            case FULL -> "`workspace_list`、`workspace_read`、`workspace_search`、"
                    + "`workspace_write`、`workspace_edit`、`workspace_delete`";
        };
    }
}
