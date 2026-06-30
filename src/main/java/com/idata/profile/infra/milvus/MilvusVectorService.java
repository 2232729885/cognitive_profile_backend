package com.idata.profile.infra.milvus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Milvus向量库基础操作封装。基于 io.milvus:milvus-sdk-java:3.0.2。
 *
 * 三个Collection（见 docs/课题四_数据库设计_v3.md 第四章）：
 *   - text_embeddings: 文本内容向量，HNSW索引，COSINE相似度，dim=4096
 *   - image_embeddings: 图像向量，同上
 *   - entity_embeddings: 实体语义向量
 *
 * TODO: 注入 io.milvus.v2.client.MilvusClientV2，在 config 包下补充连接配置
 * （host/port/token），当前先搭骨架不引入具体客户端实例，避免没配置好连接信息时启动失败。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusVectorService {

    // private final MilvusClientV2 milvusClient;  // 注入后取消注释

    /**
     * 写入文本向量。T4 runT4 步骤调用，见 docs/01-CODEGEN-CONTEXT.md 3.1节伪代码。
     *
     * @param sourceId   media_contents.id 或实体id
     * @param sourceType media_content|entity_person|entity_org|narrative
     * @param embedding  4096维向量
     */
    public String insertTextEmbedding(String sourceId, String sourceType,
                                        String platform, String language,
                                        long publishedAt, float importance,
                                        float[] embedding) {
        // TODO: 调用 milvusClient.insert("text_embeddings", ...)
        throw new UnsupportedOperationException("待补充Milvus SDK具体调用，需先在config包配置连接");
    }

    public String insertImageEmbedding(String assetId, String contentId,
                                         String platform, float aigcScore,
                                         float[] embedding) {
        // TODO: 调用 milvusClient.insert("image_embeddings", ...)
        throw new UnsupportedOperationException("待补充Milvus SDK具体调用，需先在config包配置连接");
    }

    // TODO: 补充语义检索方法（search），供 analysis线 T4检索调用使用，
    // 三路融合检索（全文ES+图谱Neo4j+向量Milvus）的RRF融合排序逻辑放在
    // analysis包下，这里只暴露纯向量检索能力
}
