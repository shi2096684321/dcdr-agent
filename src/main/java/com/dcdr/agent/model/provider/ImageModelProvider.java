package com.dcdr.agent.model.provider;

import com.dcdr.agent.common.constant.ModelType;
import com.dcdr.agent.dto.ImageRequest;
import com.dcdr.agent.dto.ImageResponse;

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
