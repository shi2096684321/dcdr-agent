package com.example.agent.model.provider;

import com.example.agent.common.constant.ModelType;
import com.example.agent.dto.ImageRequest;
import com.example.agent.dto.ImageResponse;

/**
 * 图片模型提供者接口
 */
public interface ImageModelProvider {

    String getName();

    ModelType getType();

    String getLabel();

    String getProvider();

    /** 文生图 / 图生图 */
    ImageResponse generate(ImageRequest request);
}
