package com.dcdr.agent.workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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
     * 该档位可注入模型的工作区工具清单(<b>单一真值源</b>)。
     * <p>
     * 这里的字符串必须与 {@code tool.workspace} 下各工具类的 {@code NAME} 常量一致。
     * 两个包的方向是"工具依赖本包", 本包不能反向 import 工具类(会形成包级环),
     * 所以只能靠 {@code WorkspaceAccessTest} 里的断言把漂移挡住 —— <b>不要删那个测试</b>。
     * <p>
     * 三档按包含关系构造: READ ⊂ WRITE ⊂ FULL, {@link #isWorkspaceTool} 依赖这个性质
     * 用 FULL 当全集。
     */
    public List<String> toolNames() {
        return switch (this) {
            case READ -> READ_TOOLS;
            case WRITE -> WRITE_TOOLS;
            case FULL -> FULL_TOOLS;
        };
    }

    /** 该档位是否允许调用这个工作区工具 */
    public boolean allowsTool(String toolName) {
        return toolName != null && toolNames().contains(toolName);
    }

    /**
     * 该名字是否属于工作区工具族(与档位无关)。
     * <p>
     * 判据是"FULL 档的清单里有这个名字" —— FULL 按构造已是并集, 不必再维护第二份名单。
     */
    public static boolean isWorkspaceTool(String toolName) {
        return toolName != null && FULL_TOOLS.contains(toolName);
    }

    private static final List<String> READ_TOOLS =
            List.of("workspace_list", "workspace_read", "workspace_search");

    private static final List<String> WRITE_TOOLS = with(READ_TOOLS, "workspace_write", "workspace_edit");

    private static final List<String> FULL_TOOLS = with(WRITE_TOOLS, "workspace_delete");

    private static List<String> with(List<String> base, String... more) {
        List<String> out = new ArrayList<>(base);
        out.addAll(List.of(more));
        return List.copyOf(out);
    }

    /**
     * 该档位可用的工具清单, 供 system prompt 摘要点名。
     * <p>
     * 为什么要按档位列出而不是笼统写"用 workspace_* 工具": 工具注入已按档位过滤
     * (见 {@code ToolRegistry} 的档位参数), 模型若在提示里看到未注入的名字,
     * 会反复尝试调用一个不存在的工具(报错、重试、浪费轮次)。点名可用的那几个, 它就不会去猜。
     * <p>
     * 具体清单委托给 {@link #toolNames()}: 提示文本与过滤逻辑必须是同一份名单,
     * 否则会出现"提示说能用、实际不给注入"这种自相矛盾(本方法此前就是这样漂移的)。
     */
    public String tools() {
        return toolNames().stream()
                .map(name -> "`" + name + "`")
                .collect(Collectors.joining("、"));
    }
}
