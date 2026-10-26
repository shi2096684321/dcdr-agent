package com.dcdr.agent.workspace;

import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.entity.SessionEntity;
import com.dcdr.agent.entity.WorkspaceEntity;
import com.dcdr.agent.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 把 {@link WorkspaceContextHolder} 的"谁 + 哪个会话"解析成具体工作区(V3.3 F-49)。
 * <p>
 * 解析结果回填到 holder, 同一轮工具循环内不重复查库。
 * <p>
 * <b>为什么工具层还要再查一次归属</b>: workflow / 定时任务等路径不经过 Controller,
 * 上游塞进来的 workspaceId 不可信 —— 这里统一走 {@code requireOwned} 重新校验,
 * 越权与不存在返回同样的信息。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkspaceContextResolver {

    private static final String NOT_BOUND =
            "当前会话未绑定工作区。请先在输入框上方选择要操作的目录, 再让我读写文件。";

    private final SessionRepository sessionRepository;
    private final WorkspaceService workspaceService;

    /** 当前上下文对应的工作区; 未绑定时抛带引导语的业务异常 */
    public WorkspaceEntity current() {
        String workspaceId = WorkspaceContextHolder.workspaceId();
        if (workspaceId == null) {
            String sessionId = WorkspaceContextHolder.sessionId();
            if (sessionId == null) {
                throw new BusinessException(400, NOT_BOUND);
            }
            SessionEntity session = sessionRepository.findById(sessionId)
                    .orElseThrow(() -> new BusinessException(404, "会话不存在: " + sessionId));
            workspaceId = session.getWorkspaceId();
            if (workspaceId == null || workspaceId.isBlank()) {
                throw new BusinessException(400, NOT_BOUND);
            }
            WorkspaceContextHolder.workspaceId(workspaceId);
        }
        String userId = WorkspaceContextHolder.userId();
        return workspaceService.requireOwned(workspaceId, userId, false);
    }

    /** 当前上下文的会话 ID; 未设置时抛错(撤回与日志都依赖它) */
    public String currentSessionId() {
        String sessionId = WorkspaceContextHolder.sessionId();
        if (sessionId == null) {
            throw new BusinessException(400, "当前上下文缺少会话 ID, 无法记录变更");
        }
        return sessionId;
    }
}
