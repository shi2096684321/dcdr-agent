package com.dcdr.agent.controller;

import com.dcdr.agent.common.Result;
import com.dcdr.agent.common.exception.BusinessException;
import com.dcdr.agent.dto.TtsRequest;
import com.dcdr.agent.dto.TranscriptionResponse;
import com.dcdr.agent.model.provider.AudioModelProvider;
import com.dcdr.agent.model.registry.ModelRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * 语音接口 (V1.1)
 * <p>
 * - POST /api/audio/transcribe  语音转文字 (STT, multipart)
 * - POST /api/audio/tts         文字转语音 (TTS, 返回音频流)
 */
@Slf4j
@RestController
@RequestMapping("/api/audio")
@RequiredArgsConstructor
@Tag(name = "语音", description = "语音识别 (STT) 与语音合成 (TTS) 接口")
public class AudioController {

    private final ModelRegistry registry;

    @Operation(summary = "语音转文字 (STT)",
            description = "上传音频文件, 调用 Whisper 转写为文字。支持 webm/wav/mp3, 单文件 ≤ 25MB")
    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Result<TranscriptionResponse>> transcribe(
            @Parameter(description = "音频文件", required = true,
                    schema = @Schema(type = "string", format = "binary"))
            @RequestPart("file") FilePart file) {
        if (file.filename().isEmpty()) {
            return Mono.just(Result.error(400, "音频文件为空"));
        }
        log.info("STT 转写请求: filename={}", file.filename());
        return DataBufferUtils.join(file.content()).map(dataBuffer -> {
            byte[] bytes = new byte[dataBuffer.readableByteCount()];
            dataBuffer.read(bytes);
            DataBufferUtils.release(dataBuffer);
            return bytes;
        }).map(bytes -> {
            AudioModelProvider provider = registry.getDefaultAudioProvider();
            if (provider == null) {
                throw new BusinessException(400, "未配置可用的语音模型, 请在 application.yml 中配置 agent.models.audio");
            }
            TranscriptionResponse resp = provider.transcribe(bytes, file.filename());
            return Result.success(resp);
        });
    }

    @Operation(summary = "文字转语音 (TTS)",
            description = "将文本合成为语音, 返回 audio/mpeg 音频流")
    @PostMapping("/tts")
    public Mono<ResponseEntity<org.springframework.core.io.Resource>> tts(@RequestBody TtsRequest request) {
        if (request.getText() == null || request.getText().isBlank()) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        log.info("TTS 合成请求: 文本长度={}", request.getText().length());
        return Mono.fromCallable(() -> {
            AudioModelProvider provider = registry.getDefaultAudioProvider();
            if (provider == null) {
                throw new BusinessException(400, "未配置可用的语音模型, 请在 application.yml 中配置 agent.models.audio");
            }
            byte[] audio = provider.synthesize(request);
            if (audio == null || audio.length == 0) {
                return ResponseEntity.noContent().<org.springframework.core.io.Resource>build();
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("audio/mpeg"))
                    .body(new ByteArrayResource(audio));
        });
    }
}
