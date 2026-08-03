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
    /** 尾帧参考图(可选, 仅可灵首尾帧模式支持, 需配合首帧) */
    private String endImage;
    /** 反向提示词(可选, 仅万相支持) */
    private String negativePrompt;
    /** 随机种子(可选, 用于复现, 仅万相支持) */
    private Integer seed;
    /** 视频时长: 5s / 10s */
    private String duration = "5s";
    /** 宽高比 */
    private String aspectRatio = "16:9";
}
