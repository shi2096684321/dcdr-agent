package com.dcdr.agent.model.provider;

import com.dcdr.agent.common.constant.ModelType;
import com.dcdr.agent.dto.VideoRequest;
import com.dcdr.agent.dto.GenerationDTO;

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
