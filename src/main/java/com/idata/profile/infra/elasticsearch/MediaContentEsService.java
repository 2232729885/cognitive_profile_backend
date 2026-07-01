package com.idata.profile.infra.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaContentEsService {

    private static final String MEDIA_CONTENTS_INDEX = "media_contents_index";

    private final ElasticsearchClient esClient;

    public void index(String contentId, Object document) {
        try {
            IndexResponse response = esClient.index(i -> i
                    .index(MEDIA_CONTENTS_INDEX)
                    .id(contentId)
                    .document(document));
            log.debug("Indexed media content to Elasticsearch, contentId={}, result={}",
                    contentId, response.result());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to index media content to Elasticsearch: " + contentId, e);
        }
    }
}
