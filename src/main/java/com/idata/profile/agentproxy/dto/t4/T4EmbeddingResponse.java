package com.idata.profile.agentproxy.dto.t4;

import lombok.Data;

/** qwen3-vl-embedding-8b生成的向量，维度需与Milvus Collection的dim配置一致 */
@Data
public class T4EmbeddingResponse {
    private float[] embedding;
    private String modelVersion;
}
