package com.dcdr.agent.controller;

import com.dcdr.agent.auth.ownership.OwnershipGuard;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.dto.NotifyConfigDtos;
import com.dcdr.agent.notify.NotifierRegistry;
import com.dcdr.agent.notify.NotifyChannel;
import com.dcdr.agent.notify.NotifyMessage;
import com.dcdr.agent.notify.NotifyResult;
import com.dcdr.agent.service.UserNotifyConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户级推送配置接口 (V3.3)。
 * <p>
 * 与 {@code agent.notify.*} 的关系: 部署级配置是<b>兜底</b>, 用户没配自己的 Webhook 时才会用到;
 * 用户配了就以用户的为准。所有端点都以当前登录用户为作用域, 没有"按 id 读他人配置"的入口。
 * <p>
 * 安全: Webhook 属凭据 —— 响应里只有掩码, 明文只存在服务端内存/密文列中; 地址还要过
 * {@link UserNotifyConfigService} 的域名白名单(SSRF 防护)。
 */
@Slf4j
@RestController
@RequestMapping("/api/notify-config")
@RequiredArgsConstructor
@Tag(name = "推送配置", description = "V3.3: 用户自行配置定时任务结果推送渠道")
public class NotifyConfigController {

    private final UserNotifyConfigService configService;
    private final NotifierRegistry notifierRegistry;
    private final OwnershipGuard ownershipGuard;

    @Operation(summary = "我的推送配置", description = "返回全部渠道的配置概览 (Webhook 只回显掩码)")
    @GetMapping
    public Mono<Result<List<NotifyConfigDtos.NotifyConfigView>>> list() {
        // JPA 是阻塞的: 切到 boundedElastic, 不占 netty 的请求线程(与 ChatController 同一口径)
        return ownershipGuard.currentContext()
                .subscribeOn(Schedulers.boundedElastic())
                .map(ctx -> Result.success(configService.list(ctx.userId())));
    }

    @Operation(summary = "保存推送配置", description = "Webhook / 加签密钥留空表示保留原值")
    @PutMapping
    public Mono<Result<NotifyConfigDtos.NotifyConfigView>> save(
            @RequestBody NotifyConfigDtos.SaveNotifyConfigRequest req) {
        return ownershipGuard.currentContext()
                .subscribeOn(Schedulers.boundedElastic())
                .map(ctx -> Result.success(configService.save(ctx.userId(), req)));
    }

    @Operation(summary = "删除推送配置", description = "删除后回退部署级配置")
    @DeleteMapping("/{channel}")
    public Mono<Result<Void>> delete(@PathVariable String channel) {
        return ownershipGuard.currentContext()
                .subscribeOn(Schedulers.boundedElastic())
                .map(ctx -> {
                    configService.delete(ctx.userId(), channel);
                    return Result.success();
                });
    }

    /**
     * 发一条测试消息验证连通性。
     * <p>必须先保存再测试: 凭据以数据库为准, 否则"填了没保存点测试"会拿旧配置发出去, 排查方向全错。
     */
    @Operation(summary = "测试推送", description = "用已保存的个人配置发一条测试消息 (不受节流限制)")
    @PostMapping("/test/{channel}")
    public Mono<Result<NotifyConfigDtos.NotifyTestResult>> test(@PathVariable String channel) {
        NotifyChannel ch = NotifyChannel.fromCode(channel);
        if (ch == null) {
            throw new BusinessException(400, "无法识别的通知渠道: " + channel);
        }
        // 这里会真的发一条 HTTP(渠道实现内部 block 等待响应), 必须离开 netty 请求线程
        return ownershipGuard.currentContext()
                .subscribeOn(Schedulers.boundedElastic())
                .map(ctx -> doTest(ctx.userId(), ch));
    }

    private Result<NotifyConfigDtos.NotifyTestResult> doTest(String userId, NotifyChannel ch) {
        NotifyMessage message = NotifyMessage.builder()
                .taskId("notify-config#test-" + ch.code())
                .taskName("推送配置测试")
                .channel(ch.code())
                .userId(userId)
                .status("success")
                .content("收到这条消息说明你的 Webhook 配置可用。\n"
                        + "真正的推送会在定时任务执行完成后发送, 内容是那次执行的模型输出。")
                .durationMs(0)
                .runAt(LocalDateTime.now())
                .build();
        NotifyResult r = notifierRegistry.dispatchNow(message);
        NotifyConfigDtos.NotifyTestResult dto = new NotifyConfigDtos.NotifyTestResult();
        dto.setSuccess(r.isSuccess());
        dto.setSkipped(r.isSkipped());
        dto.setChannel(ch.code());
        dto.setError(r.getError());
        dto.setReason(r.getReason());
        if (!r.isSuccess()) {
            log.info("用户 {} 推送配置测试未成功 (channel={}): {}", userId, ch.code(), r.detail());
        }
        return Result.success(dto);
    }
}
