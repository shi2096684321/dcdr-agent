package com.dcdr.agent.entity;

import com.dcdr.agent.auth.CurrentUserHolder;
import jakarta.persistence.PrePersist;
import lombok.extern.slf4j.Slf4j;

/**
 * 归属自动填充监听器 (V3.1 F-41 自动化增强)。
 * <p>
 * 通过实体的 {@code @EntityListeners(OwnershipFillListener.class)} 注册; 在 JPA {@code persist}
 * (insert) 前触发, 若实体的 {@code userId} <b>为空</b>, 则用 {@link CurrentUserHolder} 中的
 * 当前登录用户兜底填充。
 * <p>
 * <b>设计原则: 只兜底, 不覆盖</b> —— 显式设置过 userId 的实体一律保持原值。
 * 这样既不会破坏既有写入链路(它们都显式传了归属), 又能兜住"新增写入点时忘了设归属"
 * 这一类会造成<b>无主数据</b>的疏漏。
 * <p>
 * 生效范围: 仅覆盖「同步链路上的 HTTP 请求」触发的写入(ThreadLocal 可读)。
 * 定时任务 / 工作流内部 / 异步线程等无 ThreadLocal 的链路, 仍必须显式设置归属 ——
 * 此时本监听器不会填充(保持 null), 属于 fail-closed, 不会错误地写到别人名下。
 *
 * @see com.dcdr.agent.auth.CurrentUserHolder
 */
@Slf4j
public class OwnershipFillListener {

    @PrePersist
    public void fillOwnerBeforePersist(Object entity) {
        if (!(entity instanceof OwnedEntity owned)) {
            return;
        }
        String currentUserId = CurrentUserHolder.get();
        // debug 级诊断: 便于确认「同步链路 ThreadLocal 是否可读」, 默认不输出
        log.debug("归属检查: entity={}, 已设归属={}, 线程上下文用户={}",
                entity.getClass().getSimpleName(), owned.getUserId(), currentUserId);
        if (owned.getUserId() != null) {
            return; // 显式设置过, 不覆盖
        }
        if (currentUserId == null) {
            // 非同步链路(定时任务/异步线程): 无上下文可填充, 保持 null, 由业务显式负责
            return;
        }
        owned.setUserId(currentUserId);
        log.debug("归属自动填充: {} -> userId={}", entity.getClass().getSimpleName(), currentUserId);
    }
}
