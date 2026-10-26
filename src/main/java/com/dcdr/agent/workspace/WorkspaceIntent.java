package com.dcdr.agent.workspace;

/**
 * 工作区操作意图 —— 决定路径校验使用哪套 deny 规则(§6.1)。
 * <p>
 * 不与 {@link WorkspaceAccess} 合并: 一个是"这次要干什么", 一个是"这个工作区允许干什么",
 * 两者分离才能让"越权尝试"和"路径非法"给出不同的错误信息。
 */
public enum WorkspaceIntent {

    /** 列目录 / 读文件 / 搜索 */
    READ,

    /** 新建 / 修改 */
    WRITE,

    /** 删除(移入回收区) */
    DELETE
}
