package com.dcdr.agent.dto;

import lombok.Data;

/**
 * 视频生成请求(创作模式)
 */
@Data
public class VideoRequest {

    private String projectId;
    private String model;
    private String prompt;
    /** 首帧参考图(可选) */
    private String referImage;
    /** 视频时长: 5s / 10s */
    private String duration = "5s";
    /** 宽高比 */
    private String aspectRatio = "16:9";
}
