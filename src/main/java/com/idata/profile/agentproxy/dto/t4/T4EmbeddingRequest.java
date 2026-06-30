package com.idata.profile.agentproxy.dto.t4;

import lombok.Data;

/**
 * T4多模态检索Agent：文本/图像Embedding生成请求。
 * 只依赖bodyText（或图像URL），不依赖T3的融合结果，可与T3并行触发。
 */
@Data
public class T4EmbeddingRequest {
    private String text;       // 文本向量化时传
    private String imageUrl;   // 图像向量化时传
}
