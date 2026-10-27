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
        String ownerId = WorkspaceContextHolder.ownerId();
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
            // 归属按"会话的主人"判, 不按"当前请求者"判: 会话与工作区的绑定关系已经过 Controller 校验,
            // 这里要回答的是"这个会话能不能用这个工作区"。用请求者判会让 admin 操作别人的会话时
            // 被判 404 —— 而 admin 恰恰是被允许代操作的那类用户。
            ownerId = session.getUserId();
            WorkspaceContextHolder.workspaceId(workspaceId);
            WorkspaceContextHolder.ownerId(ownerId);
        }
        return workspaceService.requireOwned(workspaceId,
                ownerId != null ? ownerId : WorkspaceContextHolder.userId(), false);
    }

    /** 当前上下文的会话 ID; 未设置时抛错(撤回与日志都依赖它) */
    public String currentSessionId() {
        String sessionId = WorkspaceContextHolder.sessionId();
        if (sessionId == null) {
            throw new BusinessException(400, "当前上下文缺少会话 ID, 无法记录变更");
        }
        return sessionId;
    }

    /**
     * 按会话 ID 解析工作区, 不依赖 {@link WorkspaceContextHolder}。
     * <p>
     * 供 system prompt 摘要注入使用: 那个位置在工具循环之前、也可能跑在非工具线程上,
     * ThreadLocal 里未必有值。"没有工作区"是正常状态(绝大多数会话都没有), 返回 {@code null} 即可 ——
     * 不应该让调用方用 try/catch 来表达, 更不该把它变成一次业务异常。
     */
    public WorkspaceEntity resolveForSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        try {
            SessionEntity session = sessionRepository.findById(sessionId).orElse(null);
            if (session == null) return null;
            String workspaceId = session.getWorkspaceId();
            if (workspaceId == null || workspaceId.isBlank()) return null;
            return workspaceService.requireOwned(workspaceId, session.getUserId(), true);
        } catch (BusinessException e) {
            return null;
        }
    }
}
