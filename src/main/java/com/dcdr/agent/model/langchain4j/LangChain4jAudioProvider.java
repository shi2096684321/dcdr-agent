package com.dcdr.agent.model.langchain4j;

import com.dcdr.agent.common.constant.ModelType;
import com.dcdr.agent.config.AgentProperties;
import com.dcdr.agent.dto.TtsRequest;
import com.dcdr.agent.dto.TranscriptionResponse;
import com.dcdr.agent.model.provider.AudioModelProvider;
import dev.langchain4j.data.audio.Audio;
import dev.langchain4j.model.audio.AudioTranscriptionModel;
import dev.langchain4j.model.audio.AudioTranscriptionRequest;
import dev.langchain4j.model.audio.AudioTranscriptionResponse;
import dev.langchain4j.model.audio.TextToSpeechModel;
import dev.langchain4j.model.audio.TextToSpeechRequest;
import dev.langchain4j.model.audio.TextToSpeechResponse;
import dev.langchain4j.model.openai.OpenAiAudioTranscriptionModel;
import dev.langchain4j.model.openai.OpenAiTextToSpeechModel;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 LangChain4j 的语音模型 Provider (STT + TTS)。
 * <p>
 * 实现 {@link AudioModelProvider} 业务接口, 内核替换为框架的
 * {@link OpenAiAudioTranscriptionModel}(STT) + {@link OpenAiTextToSpeechModel}(TTS):
 * multipart 构造、鉴权头、响应解析全部由框架完成, 替代手写版
 * {@code OpenAiAudioProvider} 的 WebClient 实现。
 * <p>
 * 适配任意 OpenAI 兼容语音端点, base-url 约定(两引擎一致): 版本段(/v1 等)由 base-url 携带,
 * 框架在 baseUrl 后追加 /audio/transcriptions 与 /audio/speech。
 * <p>
 * 已知差异(可接受):
 * <ul>
 *   <li>框架 TTS 请求仅支持 text/voice 字段, 手写版透传的
 *       response_format(固定 mp3)不再下发; 框架校验文本长度上限 4096</li>
 *   <li>maxRetries=1 显式关闭框架自动重试(与图片/对话引擎策略一致)</li>
 * </ul>
 */
@Slf4j
public class LangChain4jAudioProvider implements AudioModelProvider {

    /** 对齐手写版 REQUEST_TIMEOUT(60s) */
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final AgentProperties.ModelConfig config;
    private final String name;
    private final AudioTranscriptionModel transcriptionModel;
    /** 按 TTS 模型名缓存框架模型实例(框架的 modelName 是构建期参数, 请求级 model 经缓存分发) */
    private final Map<String, TextToSpeechModel> ttsModels = new ConcurrentHashMap<>();

    public LangChain4jAudioProvider(AgentProperties.ModelConfig config) {
        this.config = config;
        this.name = config.getName();
        this.transcriptionModel = OpenAiAudioTranscriptionModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModel() != null ? config.getModel() : "whisper-1")
                .timeout(TIMEOUT)
                .maxRetries(1)
                .build();
    }

    @Override public String getName() { return name; }
    @Override public ModelType getType() { return ModelType.AUDIO; }
    @Override public String getLabel() { return config.getModel(); }
    @Override public String getProvider() { return config.getProvider(); }

    @Override
    public TranscriptionResponse transcribe(byte[] audioData, String filename) {
        try {
            Audio audio = Audio.builder()
                    .binaryData(audioData)
                    .mimeType(resolveMimeType(filename))
                    .build();
            AudioTranscriptionResponse response = transcriptionModel.transcribe(
                    AudioTranscriptionRequest.builder().audio(audio).build());
            String text = response.text();
            log.info("{} STT 转写完成, 文本长度={}", name, text != null ? text.length() : 0);
            return new TranscriptionResponse(text);
        } catch (Exception e) {
            log.error("{} STT 转写失败", name, e);
            throw new RuntimeException("语音转写失败: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] synthesize(TtsRequest request) {
        try {
            String modelName = request.getModel() != null ? request.getModel() : "tts-1";
            TextToSpeechModel ttsModel = ttsModels.computeIfAbsent(modelName, this::buildTtsModel);
            TextToSpeechRequest ttsRequest = TextToSpeechRequest.builder()
                    .text(request.getText())
                    .voice(request.getVoice() != null ? request.getVoice() : "alloy")
                    .build();
            TextToSpeechResponse response = ttsModel.synthesize(ttsRequest);
            Audio audio = response.audio();
            byte[] data = audio != null && audio.binaryData() != null ? audio.binaryData() : new byte[0];
            log.info("{} TTS 合成完成, 音频字节数={}", name, data.length);
            return data;
        } catch (Exception e) {
            log.error("{} TTS 合成失败", name, e);
            throw new RuntimeException("语音合成失败: " + e.getMessage(), e);
        }
    }

    private TextToSpeechModel buildTtsModel(String modelName) {
        return OpenAiTextToSpeechModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(modelName)
                .timeout(TIMEOUT)
                .maxRetries(1)
                .build();
    }

    /** 按文件扩展名推断 MIME 类型(框架 multipart 上传需要), 未知扩展名按 webm 前端录音格式兜底 */
    private String resolveMimeType(String filename) {
        String ext = filename != null && filename.contains(".")
                ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
                : "";
        return switch (ext) {
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "m4a" -> "audio/mp4";
            case "ogg", "oga" -> "audio/ogg";
            default -> "audio/webm";
        };
    }
}
