package com.example.agent.model.provider;

import com.example.agent.common.constant.ModelType;
import com.example.agent.dto.VideoRequest;
import com.example.agent.dto.GenerationDTO;

/**
 * 视频模型提供者接口
 */
public interface VideoModelProvider {

    String getName();

    ModelType getType();

    String getLabel();

    String getProvider();

    /** 文生视频 / 图生视频(同步等待, V1.0简化实现) */
    GenerationDTO generate(VideoRequest request);
}
