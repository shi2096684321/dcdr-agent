package com.dcdr.agent.controller;

import com.dcdr.agent.agent.governance.RetrievalHealthService;
import com.dcdr.agent.auth.SecurityContext;
import com.dcdr.agent.common.Result;
import com.dcdr.agent.dto.RetrievalHealthDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

/**
 * 检索健康度查询接口 (RAG 可观测性)。
 *
 * <p>回答"线上文档持续增长后, 检索效果有没有漂移": 零命中率 / 平均最高相似度 /
 * 检索耗时趋势, 以及可直接用于补文档或补评测集的零命中问题样本。
 * 指标口径与固定评测集({@code RagRetrievalEvalIT} / {@code RagRetrievalEvalLiveIT})互补 ——
 * 后者看"代码改动", 本接口看"真实流量"。
 *
 * <p><b>权限: 仅管理员</b>。理由: 零命中样本里含<b>用户原始提问</b>, 属运维可观测数据;
 * 普通用户不应看到他人问题, 也不承担判断"是知识盲区还是检索退化"的职责。
 * 故这里在方法内显式校验(而不是把路径收进 {@code SecurityConfig} 的
 * {@code /api/admin/**} 规则)—— 前端同样以 {@code isAdmin} 隐藏入口, 前后端双重把关。
 *
 * <p>注意: {@code SecurityConfig} 的默认兜底是 {@code /api/** -> authenticated()},
 * 即本路径对"已登录用户"是放行的, <b>真正的拦截靠本方法内的校验</b>。
 */
@Slf4j
@RestController
@RequestMapping("/api/retrieval-health")
@RequiredArgsConstructor
@Tag(name = "检索健康度", description = "RAG 检索质量可观测性: 零命中率 / 相似度趋势 / 零命中样本 (仅管理员)")
public class RetrievalHealthController {

    private final RetrievalHealthService retrievalHealthService;

    @Operation(summary = "检索健康度总览",
            description = "时间窗口内的 RAG 检索质量指标: 检索次数、平均命中数、**零命中率**、"
                    + "平均最高相似度、耗时 P95, 以及按天趋势、按知识库数量拆分、零命中问题样本"
                    + "和上一等长周期的对比值。数据来自调用链日志中的 rag 步骤, 仅统计聚合结果, "
                    + "不返回检索到的正文内容。管理员专属。")
    @GetMapping("/overview")
    public Result<RetrievalHealthDtos.HealthOverview> overview(
            @Parameter(description = "统计窗口天数 (1~180)")
            @RequestParam(defaultValue = "30") int days,
            ServerWebExchange exchange) {
        if (!SecurityContext.isAdmin(exchange)) {
            return Result.error(403, "仅管理员可查看检索健康度");
        }
        // 管理员视角 = 全局(userId 传 null), 不区分单个用户
        return Result.success(retrievalHealthService.overview(null, days));
    }
}
