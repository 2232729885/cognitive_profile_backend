package com.idata.profile.config;

import com.idata.profile.infra.elasticsearch.MediaContentEsService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ElasticsearchInitializer {

    private final MediaContentEsService esService;

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        esService.ensureIndex();
    }
}
