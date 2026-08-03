package com.dcdr.agent.model.provider;

import com.dcdr.agent.common.constant.ModelType;
import com.dcdr.agent.dto.VideoRequest;
import com.dcdr.agent.dto.GenerationDTO;

import java.util.Collections;
import java.util.List;

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

    /** 能力: 首尾帧(尾帧参考图, 需配合首帧) */
    String CAP_END_IMAGE = "end-image";
    /** 能力: 反向提示词 */
    String CAP_NEGATIVE_PROMPT = "negative-prompt";
    /** 能力: 随机种子复现 */
    String CAP_SEED = "seed";

    /**
     * 该 provider 支持的可选能力列表(用于前端动态渲染参数)。
     * 默认不支持任何可选能力, 子类按需覆盖。
     */
    default List<String> capabilities() {
        return Collections.emptyList();
    }
}
