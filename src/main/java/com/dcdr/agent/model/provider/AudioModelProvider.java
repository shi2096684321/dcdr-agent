package com.dcdr.agent.model.provider;

import com.dcdr.agent.common.constant.ModelType;
import com.dcdr.agent.dto.TtsRequest;
import com.dcdr.agent.dto.TranscriptionResponse;

/**
 * 语音模型提供者接口 (STT + TTS)
 * <p>
 * V1.1 新增: 封装语音识别(语音→文字)与语音合成(文字→语音)能力,
 * 由 {@link com.dcdr.agent.model.registry.ModelRegistry} 统一管理。
 */
public interface AudioModelProvider {

    /** 提供者唯一标识, 如 "openai-audio" */
    String getName();

    /** 模型类型, 固定为 AUDIO */
    ModelType getType();

    /** 显示标签 */
    String getLabel();

    /** 提供商名称 */
    String getProvider();

    /**
     * 语音转文字 (STT)
     *
     * @param audioData 音频二进制数据
     * @param filename  原始文件名(用于推断格式, 如 audio.webm)
     * @return 转写文本
     */
    TranscriptionResponse transcribe(byte[] audioData, String filename);

    /**
     * 文字转语音 (TTS)
     *
     * @param request 文本与音色参数
     * @return 合成的音频二进制数据(mp3)
     */
    byte[] synthesize(TtsRequest request);
}
