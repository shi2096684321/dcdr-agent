package com.dcdr.agent.agent.memory.longterm;

import com.dcdr.agent.config.AgentProperties;
import com.dcdr.agent.entity.UserEntity;
import com.dcdr.agent.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「AI 自动记忆」用户级开关的语义测试 (V3.1 P1)。
 *
 * <p>这里的每个分支都对应一种"看起来能用、实际没生效"的故障:
 * 部署关了却还在抽(白花钱)、用户关了却还在写(同意被无视)、
 * 未表态被当成明确拒绝(部署调整默认值影响不到该影响的人)。
 */
class AutoExtractPreferenceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final AgentProperties properties = new AgentProperties();

    private AutoExtractPreference preference() {
        return new AutoExtractPreference(userRepository, properties);
    }

    /** 配置部署闸门与"未表态用户的默认" */
    private void deploy(boolean enabled, boolean userDefault) {
        AgentProperties.AutoExtractConfig cfg = properties.getMemory().getAutoExtract();
        cfg.setEnabled(enabled);
        cfg.setUserDefault(userDefault);
    }

    /** 让 userId=u1 的用户存在, 且其三态取值为 choice(null=未表态) */
    private void existingUser(Boolean choice) {
        UserEntity u = new UserEntity();
        u.setId("u1");
        u.setUsername("u1");
        u.setAutoMemoryEnabled(choice);
        when(userRepository.findById("u1")).thenReturn(Optional.of(u));
    }

    @Test
    @DisplayName("部署闸门关闭: 用户就算明确开启也不生效 —— 能力根本不存在")
    void deploymentGateWins() {
        deploy(false, true);
        existingUser(Boolean.TRUE);

        assertFalse(preference().active("u1"));
        assertFalse(preference().allowed());
    }

    @Test
    @DisplayName("未表态: 跟随部署默认 —— 默认 true 生效, 默认 false 需用户自己开")
    void unsetFollowsDeploymentDefault() {
        existingUser(null);

        deploy(true, true);
        assertTrue(preference().active("u1"), "user-default=true 时跟随部署生效(CodeBuddy 的自动识别语义)");

        deploy(true, false);
        assertFalse(preference().active("u1"), "user-default=false 时需用户显式同意(opt-in)");
    }

    @Test
    @DisplayName("用户的选择优先于部署默认 —— 明确关了就立刻停")
    void userChoiceOverridesDefault() {
        deploy(true, true);
        existingUser(Boolean.FALSE);
        assertFalse(preference().active("u1"), "用户明确关闭后不该再自动写入");

        deploy(true, false);
        existingUser(Boolean.TRUE);
        assertTrue(preference().active("u1"), "用户明确开启时不受部署默认值影响");
    }

    @Test
    @DisplayName("认证关闭模式(userId 为 null): 一律不生效, 不退化成全局默认值")
    void anonymousNeverActive() {
        deploy(true, true);
        // 记忆整体依赖用户维度(注入/召回/归属校验都要求 userId), 此时功能本就不可用;
        // 若退化成"全局默认值", 没有用户的部署会误以为自己开了自动记忆
        assertFalse(preference().active(null));
        assertNull(preference().userChoice(null));
    }

    @Test
    @DisplayName("用户不存在: 按未表态处理, 不抛异常")
    void missingUserFallsBackToDefault() {
        deploy(true, true);
        when(userRepository.findById("ghost")).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> assertTrue(preference().active("ghost")));
        assertDoesNotThrow(() -> preference().update("ghost", Boolean.TRUE));
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("记录意愿: true/false 落库, null 表示恢复默认(不再表态)")
    void updateStoresTriState() {
        deploy(true, true);
        existingUser(null);

        preference().update("u1", Boolean.TRUE);
        ArgumentCaptor<UserEntity> cap = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(cap.capture());
        assertTrue(cap.getValue().getAutoMemoryEnabled());

        preference().update("u1", Boolean.FALSE);
        verify(userRepository, org.mockito.Mockito.times(2)).save(cap.capture());
        assertFalse(cap.getValue().getAutoMemoryEnabled());

        preference().update("u1", null);
        verify(userRepository, org.mockito.Mockito.times(3)).save(cap.capture());
        assertNull(cap.getValue().getAutoMemoryEnabled(), "恢复默认 = 清空表态, 重新跟随部署配置");
    }

    @Test
    @DisplayName("部署未开启时也允许记录意愿 —— 日后开启该能力, 用户不必回来点一遍")
    void choiceAcceptedWhileDeploymentDisabled() {
        deploy(false, false);
        existingUser(null);

        preference().update("u1", Boolean.TRUE);

        ArgumentCaptor<UserEntity> cap = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(cap.capture());
        assertTrue(cap.getValue().getAutoMemoryEnabled(), "存的是意愿, 不影响当前行为");
        assertFalse(preference().active("u1"), "但部署闸门关着, 当下仍然不生效");
    }

    @Test
    @DisplayName("三态: 取值与中文名都出自枚举, 界面不必再自己翻译一遍")
    void choiceMapsThreeStates() {
        deploy(true, true);

        existingUser(Boolean.TRUE);
        AutoExtractChoice on = preference().choice("u1");
        assertEquals(AutoExtractChoice.ON, on);
        assertEquals("on", on.value(), "下发取值是前后端契约, 不随 enum 名改动");
        assertFalse(on.label().isBlank(), "中文名由后端下发 —— 否则界面只能自己维护一份映射");

        existingUser(Boolean.FALSE);
        assertEquals(AutoExtractChoice.OFF, preference().choice("u1"));

        existingUser(null);
        AutoExtractChoice def = preference().choice("u1");
        assertEquals(AutoExtractChoice.DEFAULT, def,
                "未表态必须是独立取值 —— 界面靠它说明'当前是跟随部署配置'");
        assertEquals("default", def.value());
    }
}
