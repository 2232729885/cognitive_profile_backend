package com.idata.profile.config;

import com.idata.profile.infra.elasticsearch.EntityEsService;
import com.idata.profile.infra.elasticsearch.MediaAssetEsService;
import com.idata.profile.infra.elasticsearch.MediaContentEsService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ElasticsearchInitializer {

    private final MediaContentEsService esService;
    private final EntityEsService entityEsService;
    private final MediaAssetEsService mediaAssetEsService;

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        esService.ensureIndex();
        entityEsService.ensureIndex();
        mediaAssetEsService.ensureIndex();
    }
}
