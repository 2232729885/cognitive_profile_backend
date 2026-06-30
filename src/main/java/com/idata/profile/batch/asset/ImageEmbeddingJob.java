package com.idata.profile.batch.asset;

import com.idata.profile.agentproxy.AgentProxyClient;
import com.idata.profile.agentproxy.dto.t4.T4EmbeddingRequest;
import com.idata.profile.agentproxy.dto.t4.T4EmbeddingResponse;
import com.idata.profile.entity.content.MediaAsset;
import com.idata.profile.infra.milvus.MilvusVectorService;
import com.idata.profile.mapper.content.MediaAssetMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * T4异步图像向量化任务。扫描还没生成向量的图片/视频，与入库主流程解耦。
 * 见 docs/课题四_数据处理流程_v2.md 第五章 Step4。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageEmbeddingJob {

    private static final int BATCH_LIMIT = 200;

    private final MediaAssetMapper mediaAssetMapper;
    private final AgentProxyClient agentProxyClient;
    private final MilvusVectorService milvusVectorService;

    @Scheduled(fixedDelay = 10 * 60 * 1000)  // 每10分钟
    public void run() {
        List<MediaAsset> pending = mediaAssetMapper.selectPendingEmbedding(BATCH_LIMIT);
        for (MediaAsset asset : pending) {
            try {
                T4EmbeddingRequest request = new T4EmbeddingRequest();
                request.setImageUrl(asset.getSourceUrl() != null ? asset.getSourceUrl() : asset.getStorageUri());

                T4EmbeddingResponse response = agentProxyClient.call(
                        "T4", "generate_image_embedding", request, T4EmbeddingResponse.class);

                String vectorId = milvusVectorService.insertImageEmbedding(
                        asset.getId().toString(),
                        asset.getContentId() != null ? asset.getContentId().toString() : null,
                        null,  // TODO: platform需从关联的media_contents查询
                        asset.getAigcScore() != null ? asset.getAigcScore().floatValue() : 0f,
                        response.getEmbedding());

                asset.setEmbeddingId(vectorId);
                mediaAssetMapper.updateById(asset);
            } catch (Exception e) {
                log.error("图像向量化失败, assetId={}", asset.getId(), e);
                // 不中断，继续下一个，下一轮会重新尝试（embeddingId仍为null）
            }
        }
        if (!pending.isEmpty()) {
            log.info("本轮图像向量化完成: {} 条", pending.size());
        }
    }
}
