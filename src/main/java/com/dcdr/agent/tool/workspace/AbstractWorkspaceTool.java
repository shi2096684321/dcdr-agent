package com.dcdr.agent.tool.workspace;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.tool.AbstractTool;

/**
 * 工作区工具公共基类(V3.3 F-49)。
 * <p>
 * 两件事:
 * <ol>
 *   <li>统一把 {@link BusinessException} 转成模型能读懂的失败文案 —— 不抛栈、不吞错,
 *       让模型知道"为什么不行"而不是拿到一句 "工具执行失败"</li>
 *   <li>提示词里统一说明"路径必须是工作区内的相对路径", 减少模型尝试绝对路径的无效轮次</li>
 * </ol>
 */
public abstract class AbstractWorkspaceTool extends AbstractTool {

    /** 所有工作区工具共享的路径约定说明, 拼在每个工具的 description 后面 */
    protected static final String PATH_NOTE =
            " path 必须是当前工作区内的相对路径(如 src/main/A.java), 不能是绝对路径, 也不能包含 ..";

    /** 执行体的受检包装: 业务异常转文案, 其余异常交给上层记录 */
    protected String run(ThrowingSupplier body) {
        try {
            return body.get();
        } catch (BusinessException e) {
            return "操作失败: " + e.getMessage();
        }
    }

    @FunctionalInterface
    protected interface ThrowingSupplier {
        String get();
    }
}
