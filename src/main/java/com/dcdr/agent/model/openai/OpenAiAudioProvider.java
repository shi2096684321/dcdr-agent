package com.dcdr.agent.model.openai;

import com.dcdr.agent.common.constant.ModelType;
import com.dcdr.agent.dto.TtsRequest;
import com.dcdr.agent.dto.TranscriptionResponse;
import com.dcdr.agent.model.provider.AudioModelProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * OpenAI 语音模型 Provider
 * <p>
 * STT: Whisper-1 (POST /v1/audio/transcriptions, multipart)
 * TTS: TTS-1     (POST /v1/audio/speech, 返回 mp3 字节流)
 * <p>
 * 未配置有效 API Key (demo-key) 时进入演示模式: STT 返回提示文本, TTS 返回空音频。
 */
@Slf4j
public class OpenAiAudioProvider implements AudioModelProvider {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String name;
    private final boolean demo;

    public OpenAiAudioProvider(String baseUrl, String apiKey, String name) {
        this.webClient = WebClient.builder().baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        this.name = name;
        this.demo = apiKey == null || apiKey.isBlank() || apiKey.contains("demo");
    }

    @Override public String getName() { return name; }
    @Override public ModelType getType() { return ModelType.AUDIO; }
    @Override public String getLabel() { return "OpenAI 语音 (Whisper + TTS)"; }
    @Override public String getProvider() { return "openai"; }

    @Override
    public TranscriptionResponse transcribe(byte[] audioData, String filename) {
        if (demo) {
            log.warn("OpenAI STT 处于演示模式(API Key 未配置), 返回提示文本");
            return new TranscriptionResponse("[演示模式] 未配置有效的 OpenAI API Key, 语音转写不可用");
        }
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("model", "whisper-1");
        builder.part("file", new ByteArrayResource(audioData) {
            @Override public String getFilename() { return filename; }
        }).contentType(MediaType.APPLICATION_OCTET_STREAM);

        try {
            String resp = webClient.post().uri("/v1/audio/transcriptions")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .retrieve().bodyToMono(String.class)
                    .timeout(REQUEST_TIMEOUT)
                    .block();
            JsonNode root = mapper.readTree(resp);
            String text = root.path("text").asText();
            log.info("OpenAI STT 转写完成, 文本长度={}", text.length());
            return new TranscriptionResponse(text);
        } catch (Exception e) {
            log.error("OpenAI STT 转写失败", e);
            throw new RuntimeException("语音转写失败: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] synthesize(TtsRequest request) {
        if (demo) {
            log.warn("OpenAI TTS 处于演示模式(API Key 未配置), 返回空音频");
            return new byte[0];
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("model", request.getModel() != null ? request.getModel() : "tts-1");
        body.put("input", request.getText());
        body.put("voice", request.getVoice() != null ? request.getVoice() : "alloy");
        body.put("response_format", request.getFormat() != null ? request.getFormat() : "mp3");

        try {
            byte[] audio = webClient.post().uri("/v1/audio/speech")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body.toString())
                    .retrieve().bodyToMono(byte[].class)
                    .timeout(REQUEST_TIMEOUT)
                    .block();
            log.info("OpenAI TTS 合成完成, 音频字节数={}", audio != null ? audio.length : 0);
            return audio;
        } catch (Exception e) {
            log.error("OpenAI TTS 合成失败", e);
            throw new RuntimeException("语音合成失败: " + e.getMessage(), e);
        }
    }
}
