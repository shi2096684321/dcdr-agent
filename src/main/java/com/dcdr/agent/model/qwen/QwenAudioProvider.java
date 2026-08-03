package com.dcdr.agent.model.qwen;

import com.dcdr.agent.common.constant.ModelType;
import com.dcdr.agent.dto.TtsRequest;
import com.dcdr.agent.dto.TranscriptionResponse;
import com.dcdr.agent.model.provider.AudioModelProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 阿里云百炼（DashScope）语音合成 Provider。
 *
 * <p>百炼 Sambert 系列（如 {@code sambert-zhide-v1}）采用 WebSocket 实时合成协议
 * （{@code wss://{host}/api-ws/v1/inference/}）：run-task 指令一次性发送全部文本，
 * 服务端通过二进制帧持续回传音频流，结束时下发 task-finished 事件。</p>
 *
 * <p>base-url 约定为不含路径的域名（如 https://dashscope.aliyuncs.com），WebSocket 端点
 * 由其推导为 wss://{host}/api-ws/v1/inference/；若使用北京地域专属域名可同样配置到 base-url。</p>
 *
 * <p>注意：百炼语音合成仅提供 TTS 能力；其语音识别（Paraformer）要求公网可访问的
 * 音频 URL，不适用于前端直传二进制音频的场景，故 {@link #transcribe} 直接抛出明确异常，
 * 提醒调用方使用 OpenAI Whisper 等 provider 处理 STT。</p>
 */
@Slf4j
public class QwenAudioProvider implements AudioModelProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final String name;
    private final String model;

    public QwenAudioProvider(String baseUrl, String apiKey, String name, String model) {
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "https://dashscope.aliyuncs.com" : baseUrl;
        this.apiKey = apiKey;
        this.name = name;
        this.model = (model == null || model.isBlank()) ? "sambert-zhide-v1" : model;
    }

    /**
     * 由配置中的 base-url 推导出 Sambert WebSocket 端点。
     * <ul>
     *   <li>https://dashscope.aliyuncs.com → wss://dashscope.aliyuncs.com/api-ws/v1/inference/</li>
     *   <li>https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com → 对应专属域名</li>
     * </ul>
     */
    private String wsUrl() {
        String ws = baseUrl.replaceFirst("^https?://", "wss://");
        if (!ws.endsWith("/")) {
            ws += "/";
        }
        if (!ws.contains("/api-ws/")) {
            ws += "api-ws/v1/inference/";
        }
        return ws;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ModelType getType() {
        return ModelType.AUDIO;
    }

    @Override
    public String getLabel() {
        return "阿里云百炼 语音合成 (Sambert)";
    }

    @Override
    public String getProvider() {
        return "qwen";
    }

    @Override
    public TranscriptionResponse transcribe(byte[] audioData, String filename) {
        throw new UnsupportedOperationException(
                "百炼(Qwen)语音合成 provider 仅提供 TTS 能力。前端直传二进制音频的 STT 请使用 OpenAI Whisper 等 provider；"
                        + "百炼 Paraformer 语音识别需要公网可访问的音频 URL，不适用于本地二进制上传场景。"
        );
    }

    @Override
    public byte[] synthesize(TtsRequest request) {
        if (apiKey == null || apiKey.isBlank() || apiKey.contains("demo")) {
            throw new IllegalStateException("缺少有效的 DASHSCOPE_API_KEY，无法调用百炼 Sambert TTS（请配置百炼 API Key 并将 audio.qwen-audio.enabled 设为 true）");
        }
        String text = request.getText();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("TTS 文本不能为空");
        }

        String taskId = "task-" + UUID.randomUUID();
        String runTask = buildRunTask(taskId, text);

        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        AtomicReference<String> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        WebSocketClient client = new ReactorNettyWebSocketClient();
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        headers.add("X-DashScope-DataInspection", "enable");

        try {
            client.execute(URI.create(wsUrl()), headers, session ->
                    session.send(Mono.just(session.textMessage(runTask)))
                            .thenMany(session.receive())
                            .doOnNext(msg -> {
                                if (msg.getType() == WebSocketMessage.Type.BINARY) {
                                    org.springframework.core.io.buffer.DataBuffer buf = msg.getPayload();
                                    byte[] chunk = new byte[buf.readableByteCount()];
                                    buf.read(chunk);
                                    org.springframework.core.io.buffer.DataBufferUtils.release(buf);
                                    synchronized (audio) {
                                        audio.write(chunk, 0, chunk.length);
                                    }
                                } else if (msg.getType() == WebSocketMessage.Type.TEXT) {
                                    String payload = msg.getPayloadAsText();
                                    String event = parseEvent(payload);
                                    if ("task-failed".equals(event)) {
                                        errorRef.set(payload);
                                        latch.countDown();
                                        session.close().subscribe();
                                    } else if ("task-finished".equals(event)) {
                                        latch.countDown();
                                        session.close().subscribe();
                                    }
                                }
                            })
                            .then()
            ).block(Duration.ofSeconds(150));
        } catch (Exception e) {
            throw new IllegalStateException("Sambert TTS 调用异常: " + e.getMessage(), e);
        }

        try {
            if (!latch.await(150, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Sambert TTS 超时（150s 内未收到 task-finished）");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Sambert TTS 被中断", ie);
        }

        if (errorRef.get() != null) {
            throw new IllegalStateException("Sambert TTS 失败: " + errorRef.get());
        }
        if (audio.size() == 0) {
            throw new IllegalStateException("Sambert TTS 未返回音频数据");
        }
        log.info("Sambert TTS 合成完成, 模型={}, 音频字节数={}", model, audio.size());
        return audio.toByteArray();
    }

    private String buildRunTask(String taskId, String text) {
        try {
            var root = MAPPER.createObjectNode();
            var header = root.putObject("header");
            header.put("action", "run-task");
            header.put("task_id", taskId);
            header.put("streaming", "out");
            var payload = root.putObject("payload");
            payload.put("task_group", "audio");
            payload.put("task", "tts");
            payload.put("function", "SpeechSynthesizer");
            payload.put("model", model);
            var params = payload.putObject("parameters");
            params.put("text_type", "plain");
            params.put("sample_rate", 48000);
            params.put("format", "mp3");
            var input = payload.putObject("input");
            input.put("text", text);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("构建 Sambert run-task 指令失败", e);
        }
    }

    private String parseEvent(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            JsonNode header = node.get("header");
            if (header != null && !header.isMissingNode()) {
                return header.path("event").asText("");
            }
            return node.path("event").asText("");
        } catch (Exception e) {
            return "";
        }
    }
}
