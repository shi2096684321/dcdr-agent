package com.dcdr.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 语音转文字 (STT) 响应
 */
@Data
@Schema(description = "语音转文字响应")
public class TranscriptionResponse {

    @Schema(description = "转写文本", example = "你好，介绍一下你自己")
    private String text;

    public TranscriptionResponse() {
    }

    public TranscriptionResponse(String text) {
        this.text = text;
    }
}
