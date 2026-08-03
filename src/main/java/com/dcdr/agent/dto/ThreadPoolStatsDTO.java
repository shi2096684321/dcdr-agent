package com.dcdr.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 生成线程池运行态快照, 供前端展示"可用线程"等资源占用情况。
 */
@Data
@Schema(description = "生成线程池状态")
public class ThreadPoolStatsDTO {

    /** 图片生成线程池快照 */
    @Schema(description = "图片生成线程池")
    private PoolStats imagePool;

    /** 视频生成线程池快照 */
    @Schema(description = "视频生成线程池")
    private PoolStats videoPool;

    @Data
    @Schema(description = "单个线程池的运行态快照")
    public static class PoolStats {
        @Schema(description = "核心线程数", example = "4")
        private int corePoolSize;

        @Schema(description = "最大线程数", example = "8")
        private int maxPoolSize;

        @Schema(description = "当前线程数(已创建的线程)", example = "3")
        private int poolSize;

        @Schema(description = "正在执行任务的活跃线程数", example = "2")
        private int activeCount;

        @Schema(description = "等待队列中排队的任务数", example = "0")
        private int queueSize;

        @Schema(description = "当前可立即投入的新任务数(最大线程数 - 活跃线程数)", example = "6")
        private int available;
    }
}
