package com.dcdr.agent.agent.memory.longterm;

import com.dcdr.agent.config.AgentProperties;
import com.dcdr.agent.entity.UserEntity;
import com.dcdr.agent.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 「AI 自动记忆」的用户级开关 (V3.1 P1) —— 本类<b>独占</b>这套判定语义。
 *
 * <p><b>为什么要有用户级开关</b>: 配置项 {@code agent.memory.auto-extract.enabled} 是部署方的
 * 能力闸门(它决定"这个部署要不要付这笔模型费用"), 而"要不要让 AI 替我记东西"是用户自己的事。
 * 两者语义不同, 不能合并 —— 参考 CodeBuddy: 它同样是"AI 自动识别并保存"+ 一个"可全局关闭的开关",
 * 区别是这里把同意权也交还给用户。
 *
 * <p><b>生效规则</b>: {@code 生效 = 部署允许 && (用户选择 ?? 部署默认)}。
 * 两个方向都要满足, 少一个都不生效:
 * <ul>
 *   <li>部署关闭 → 用户就算开了也不生效(能力不存在, 界面上开关是禁用的);</li>
 *   <li>用户关闭 → 立即停写, 已有记忆原样保留(不必也不该顺手删掉)。</li>
 * </ul>
 *
 * <p><b>三态的用处</b>: 用户表那列可空, {@code null} = 从未表态。这样部署方日后调整
 * {@code user-default} 时, 只有"真没选过"的人会跟着变, 明确选过的人不受影响。
 *
 * <p><b>判定语义只写在这里</b>: 抽取门控({@code MemoryAutoExtractor#shouldExtract})与
 * 概览展示({@code LongTermMemoryService#summary})都调本类 —— 两处各判一次迟早会判出两种结果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutoExtractPreference {

    private final UserRepository userRepository;
    private final AgentProperties properties;

    /** 部署方是否允许这一能力(全局闸门) */
    public boolean allowed() {
        return properties.getMemory().getAutoExtract().isEnabled();
    }

    /** 能力开启后, 未表态用户是否默认生效 */
    public boolean userDefault() {
        return properties.getMemory().getAutoExtract().isUserDefault();
    }

    /**
     * 用户自己的选择。
     *
     * @return {@code TRUE}/{@code FALSE} = 已表态; {@code null} = 未表态或找不到用户
     */
    public Boolean userChoice(String userId) {
        if (userId == null) return null;
        return userRepository.findById(userId)
                .map(UserEntity::getAutoMemoryEnabled)
                .orElse(null);
    }

    /**
     * 本用户最终是否生效 —— 抽取门控与界面展示都以它为准。
     * <p>
     * 认证关闭模式下 {@code userId} 为 null: 记忆力整体依赖用户维度(注入、召回、归属校验都要),
     * 此时记忆功能本就不可用, 这里返回 false 与之保持一致, 而不是退化成全局默认值
     * —— 那会让"没有用户"的部署误以为自己开了自动记忆。
     */
    public boolean active(String userId) {
        if (userId == null || !allowed()) return false;
        Boolean choice = userChoice(userId);
        return choice != null ? choice : userDefault();
    }

    /**
     * 用户选择的三态(未表态 → {@link AutoExtractChoice#DEFAULT})。
     * <p>返回枚举而不是字符串: 概览要同时下发"取值"(前后端契约)与"中文名"(界面展示),
     * 两者都由枚举给出 —— 调用方不必再自己写一遍 {@code null → "default"} 的映射,
     * 界面上那套"取值 → 人话"的映射表也不必再维护一份。
     */
    public AutoExtractChoice choice(String userId) {
        return AutoExtractChoice.of(userChoice(userId));
    }

    /**
     * 记录用户的选择。
     *
     * @param enabled {@code true}/{@code false} = 明确表态; {@code null} = 恢复默认(不再表态)
     *                <p>
     *                刻意<b>不因"部署未开启"而拒绝写入</b>: 那只是"我的意愿", 存下来不影响任何行为
     *                (此时 {@link #active} 恒为 false)。好处是部署方日后开启该能力时,
     *                用户早先表达的意愿立刻生效, 不必再让每个人回来点一遍。
     */
    @Transactional
    public void update(String userId, Boolean enabled) {
        if (userId == null) return;   // 认证关闭模式: 没有用户行可写
        UserEntity user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            // 用户不存在(如 token 指向已删除用户): 静默跳过, 不因此报错
            log.warn("自动记忆开关: 用户不存在, 忽略设置请求 userId={}", userId);
            return;
        }
        user.setAutoMemoryEnabled(enabled);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        log.info("自动记忆开关: user={}, choice={}", userId, AutoExtractChoice.of(enabled).value());
    }
}
