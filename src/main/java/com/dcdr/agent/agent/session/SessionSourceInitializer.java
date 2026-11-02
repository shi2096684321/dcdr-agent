package com.dcdr.agent.agent.session;

import com.dcdr.agent.repository.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * V3.3 会话来源一次性回填: 把存量里混在对话列表中的自动执行会话改归正确来源。
 *
 * <p><b>为什么必须在应用里做, 而不是只写进 SQL 脚本</b>: 本地与容器部署都靠
 * {@code ddl-auto=update} 自动加列, 没人会去手工执行 {@code docs/sql/v3.3_schema.sql}。
 * 若只在脚本里回填, 用户升级后打开对话页会看到"自动会话还在, 新任务对话里却没有" ——
 * 只能靠手动一条条删, 等于这次改动没生效。
 *
 * <p><b>识别依据只有标题前缀</b>: 旧版把标题写成 {@code schedule:任务名} /
 * {@code workflow:步骤名}, 但没有落来源字段。标题在首个用户问题到达后会被改写,
 * 那部分前缀已丢, 无法反推, 本类对它们不做处理(留在对话列表, 由用户自行删除) ——
 * 这是已知的一点点不彻底, 好过为了"猜"而误伤用户自己的会话。
 *
 * <p>幂等: 只更新 {@code source='user'} 且标题带前缀的行, 跑第二次影响 0 行。
 * 无匹配时 PostgreSQL 不写任何行, 代价只是一次顺序扫描, 放在启动期可以接受。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionSourceInitializer implements ApplicationRunner {

    private final SessionRepository sessionRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        try {
            int scheduled = sessionRepository.reclassifyLegacyAutoSessions(
                    SessionSource.SCHEDULE.code(), "schedule:%");
            int workflow = sessionRepository.reclassifyLegacyAutoSessions(
                    SessionSource.WORKFLOW.code(), "workflow:%");
            if (scheduled + workflow > 0) {
                log.info("V3.3 会话来源回填: 定时任务 {} 条, 工作流 {} 条 (从对话列表移入「任务对话」)",
                        scheduled, workflow);
            }
        } catch (Exception e) {
            // 回填失败不该阻断启动: 最坏结果是一批旧自动会话继续留在对话列表里
            log.warn("V3.3 会话来源回填失败: {}", e.getMessage());
        }
    }
}
