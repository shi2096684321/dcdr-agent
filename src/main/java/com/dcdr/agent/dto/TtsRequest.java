package com.dcdr.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 文字转语音 (TTS) 请求
 */
@Data
@Schema(description = "文字转语音请求")
public class TtsRequest {

    @Schema(description = "待合成的文本（≤ 4000 字符）", example = "你好！我是一个AI助手")
    private String text;

    @Schema(description = "音色", allowableValues = {"alloy", "echo", "fable", "onyx", "nova", "shimmer"},
            example = "alloy")
    private String voice = "alloy";

    @Schema(description = "TTS 模型", example = "tts-1")
    private String model = "tts-1";

    @Schema(description = "输出格式", example = "mp3")
    private String format = "mp3";
}
