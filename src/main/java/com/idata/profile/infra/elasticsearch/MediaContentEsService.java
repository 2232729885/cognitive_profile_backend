package com.idata.profile.infra.elasticsearch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * media_contents_index 全文检索封装。基于 co.elastic.clients:elasticsearch-java。
 * ES不是主存储，数据由PG同步，索引mapping见 docs/课题四_数据库设计_v3.md 第三章。
 *
 * T4 runT4 步骤调用 index() 方法同步内容到ES；
 * analysis线的多模态检索（F6）调用 search() 方法做全文检索，
 * 与 Neo4j图谱检索、Milvus向量检索三路融合排序。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaContentEsService {

    // private final ElasticsearchClient esClient;  // 注入后取消注释

    public void index(String contentId, Object document) {
        // TODO: esClient.index(i -> i.index("media_contents_index").id(contentId).document(document))
        throw new UnsupportedOperationException("待补充ES客户端具体调用");
    }

    // TODO: 补充全文检索方法，供analysis.orchestrator的T4检索调用使用
}
