package com.dcdr.agent.auth.ownership.provider;

import com.dcdr.agent.auth.ownership.Ownership;
import com.dcdr.agent.auth.ownership.ResourceOwnershipProvider;
import com.dcdr.agent.auth.ownership.ResourceType;
import com.dcdr.agent.repository.ScheduledTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 定时任务归属 Provider (V3.1 F-41)。
 * <p>
 * 迁移前 {@code ScheduledTaskService} 对 get/update/delete/trigger 内联
 * {@code Objects.equals(userId)} 判断, 且<b>不支持 admin 放行</b> —— 迁移后
 * admin 可管理全部任务(与知识库口径对齐), 越权返回码统一为 404。
 */
@Component
@RequiredArgsConstructor
public class ScheduledTaskOwnershipProvider implements ResourceOwnershipProvider {

    private final ScheduledTaskRepository scheduledTaskRepository;

    @Override
    public ResourceType type() {
        return ResourceType.SCHEDULED_TASK;
    }

    @Override
    public Ownership load(String id) {
        return scheduledTaskRepository.findById(id)
                .map(task -> Ownership.of(task.getUserId()))
                .orElse(null);
    }

    @Override
    public String notFoundMessage(String id) {
        return "定时任务不存在";
    }
}
