package com.dcdr.agent.controller;

import com.dcdr.agent.common.Result;
import com.dcdr.agent.dto.ThreadPoolStatsDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行时系统状态查询。
 */
@Slf4j
@RestController
@RequestMapping("/api/system")
@Tag(name = "系统信息", description = "运行时状态查询")
public class SystemController {

    @Autowired
    @Qualifier("generationExecutor")
    private ThreadPoolTaskExecutor generationExecutor;

    @Operation(summary = "查询生成线程池状态",
            description = "返回图片/视频生成线程池的活跃线程、队列与可用线程数, 供前端展示资源占用")
    @GetMapping("/thread-pool")
    public Result<ThreadPoolStatsDTO> threadPool() {
        ThreadPoolStatsDTO stats = new ThreadPoolStatsDTO();
        stats.setCorePoolSize(generationExecutor.getCorePoolSize());
        stats.setMaxPoolSize(generationExecutor.getMaxPoolSize());
        stats.setPoolSize(generationExecutor.getPoolSize());
        stats.setActiveCount(generationExecutor.getActiveCount());
        stats.setQueueSize(generationExecutor.getThreadPoolExecutor().getQueue().size());
        stats.setAvailable(Math.max(0, stats.getMaxPoolSize() - stats.getActiveCount()));
        return Result.success(stats);
    }
}
