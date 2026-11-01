package com.dcdr.agent.notify;

import com.dcdr.agent.service.UserNotifyConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 分发器行为测试。
 * <p>
 * 核心是区分三种结果: 成功 / <b>跳过</b> / 失败。
 * 跳过 (总开关关闭、触发节流) 是预期行为, 失败才是异常 —— 若把跳过也记成失败,
 * 前端会持续显示"推送失败", 用户只能把整个功能关掉。
 */
class NotifierRegistryTest {

    private NotifyProperties props;
    private AtomicInteger sendCount;
    private NotifierRegistry registry;
    /** V3.4: 用户级推送配置; 测试关心的是"分发语义", 凭据解析用 mock 代替数据库 */
    private UserNotifyConfigService notifyConfigService;

    /** 只计数的假渠道, 避免测试触网 */
    private final class CountingNotifier implements Notifier {
        @Override
        public NotifyChannel channel() {
            return NotifyChannel.WECOM_BOT;
        }

        @Override
        public NotifyResult send(NotifyMessage message) {
            sendCount.incrementAndGet();
            return NotifyResult.success(channel().code());
        }

        @Override
        public int maxSize() {
            return 4096;
        }

        @Override
        public NotifyTemplate.Unit unit() {
            return NotifyTemplate.Unit.BYTES;
        }

        @Override
        public String truncateSuffix() {
            return "";
        }
    }

    @BeforeEach
    void setUp() {
        props = new NotifyProperties();
        props.setEnabled(true);
        props.setThrottleWindowMs(60_000L);
        sendCount = new AtomicInteger();
        notifyConfigService = mock(UserNotifyConfigService.class);
        registry = new NotifierRegistry(props, List.of(new CountingNotifier()), notifyConfigService);
        registry.init();
    }

    @Test
    @DisplayName("总开关关闭时直接跳过, 不发起任何调用")
    void globalSwitchOffSkips() {
        props.setEnabled(false);
        NotifyResult r = registry.dispatch(message("s1", "success"));
        assertTrue(r.isSkipped());
        assertFalse(r.isSuccess());
        assertEquals(0, sendCount.get());
        assertTrue(r.getReason().contains("总开关"));
    }

    @Test
    @DisplayName("总开关关闭但用户已配置个人渠道时仍然发送")
    void userConfigAllowsSendWhenGlobalSwitchOff() {
        props.setEnabled(false);
        when(notifyConfigService.credentials("u1")).thenReturn(Map.of(
                NotifyChannel.WECOM_BOT, userCredential(true)));

        NotifyMessage m = userMessage("u1");
        assertTrue(registry.dispatch(m).isSuccess());
        assertEquals(1, sendCount.get());
        assertNotNull(m.getWebhookUrl(), "用户凭据应填入消息, 供渠道实现优先使用");
    }

    @Test
    @DisplayName("个人渠道已停用且部署未配置时, 明确报停用而不是报未配置")
    void disabledUserConfigWithoutGlobalFails() {
        // 全局开关开着, 但部署级 webhook 没配: 此时用户"配了又关掉"必须说清原因
        when(notifyConfigService.credentials("u1")).thenReturn(Map.of(
                NotifyChannel.WECOM_BOT, userCredential(false)));

        NotifyResult r = registry.dispatch(userMessage("u1"));
        assertFalse(r.isSuccess());
        assertFalse(r.isSkipped());
        assertTrue(r.getError().contains("已停用"), "应提示已停用, 实际: " + r.getError());
        assertEquals(0, sendCount.get());
    }

    @Test
    @DisplayName("成功消息在节流窗口内只发送一次")
    void successIsThrottledWithinWindow() {
        assertTrue(registry.dispatch(message("s1", "success")).isSuccess());
        NotifyResult second = registry.dispatch(message("s1", "success"));
        assertTrue(second.isSkipped());
        assertFalse(second.isSuccess());
        assertEquals(1, sendCount.get());
    }

    @Test
    @DisplayName("不同任务互不影响节流")
    void throttleIsPerTask() {
        registry.dispatch(message("s1", "success"));
        assertTrue(registry.dispatch(message("s2", "success")).isSuccess());
        assertEquals(2, sendCount.get());
    }

    @Test
    @DisplayName("失败消息不受节流限制")
    void failureIsNeverThrottled() {
        registry.dispatch(message("s1", "failed"));
        registry.dispatch(message("s1", "failed"));
        assertEquals(2, sendCount.get());
    }

    @Test
    @DisplayName("节流窗口为 0 时不节流")
    void zeroWindowDisablesThrottle() {
        props.setThrottleWindowMs(0);
        registry.dispatch(message("s1", "success"));
        registry.dispatch(message("s1", "success"));
        assertEquals(2, sendCount.get());
    }

    @Test
    @DisplayName("无法识别的渠道名直接失败, 不静默降级成默认渠道")
    void unknownChannelFails() {
        NotifyMessage m = NotifyMessage.builder()
                .taskId("s1").taskName("t").status("success").channel("feishu")
                .content("x").durationMs(1).runAt(LocalDateTime.now()).build();
        NotifyResult r = registry.dispatch(m);
        assertFalse(r.isSuccess());
        assertFalse(r.isSkipped());
        assertTrue(r.getError().contains("无法识别"));
        assertEquals(0, sendCount.get());
    }

    @Test
    @DisplayName("渠道没有实现时给出明确原因, 而不是默默无反应")
    void channelWithoutImplementationFails() {
        NotifierRegistry empty = new NotifierRegistry(props, List.of(), notifyConfigService);
        empty.init();
        NotifyResult r = empty.dispatch(message("s1", "success"));
        assertFalse(r.isSuccess());
        assertTrue(r.getError().contains("暂无实现"));
    }

    @Test
    @DisplayName("空消息被跳过而非抛异常")
    void nullMessageSkipped() {
        assertTrue(registry.dispatch(null).isSkipped());
    }

    @Test
    @DisplayName("按任务指定渠道路由到对应实现")
    void routesToRequestedChannel() {
        NotifyProperties p = new NotifyProperties();
        p.setEnabled(true);
        p.setThrottleWindowMs(0);
        AtomicInteger dingCount = new AtomicInteger();
        Notifier ding = new Notifier() {
            @Override
            public NotifyChannel channel() {
                return NotifyChannel.DINGTALK_BOT;
            }

            @Override
            public NotifyResult send(NotifyMessage message) {
                dingCount.incrementAndGet();
                return NotifyResult.success(channel().code());
            }

            @Override
            public int maxSize() {
                return 18_000;
            }

            @Override
            public NotifyTemplate.Unit unit() {
                return NotifyTemplate.Unit.CHARS;
            }

            @Override
            public String truncateSuffix() {
                return "";
            }
        };
        NotifierRegistry reg = new NotifierRegistry(p, List.of(new CountingNotifier(), ding), notifyConfigService);
        reg.init();

        NotifyMessage m = NotifyMessage.builder()
                .taskId("s1").taskName("t").status("success").channel("dingtalk_bot")
                .content("x").durationMs(1).runAt(LocalDateTime.now()).build();
        assertTrue(reg.dispatch(m).isSuccess());
        assertEquals(1, dingCount.get(), "应当路由到钉钉渠道");
        assertEquals(0, sendCount.get(), "不应误发给企业微信渠道");
    }

    /** 用户级凭据 (configured=true, 可选启用) */
    private UserNotifyConfigService.Credential userCredential(boolean enabled) {
        return new UserNotifyConfigService.Credential(
                "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=x", null, null, true, enabled);
    }

    private NotifyMessage userMessage(String userId) {
        return NotifyMessage.builder()
                .taskId("s1").taskName("t").status("success").content("x")
                .durationMs(1).runAt(LocalDateTime.now()).userId(userId).build();
    }

    private NotifyMessage message(String taskId, String status) {
        return NotifyMessage.builder()
                .taskId(taskId)
                .taskName("测试任务")
                .status(status)
                .content("输出")
                .error("failed".equals(status) ? "boom" : null)
                .durationMs(10)
                .runAt(LocalDateTime.now())
                .build();
    }
}
