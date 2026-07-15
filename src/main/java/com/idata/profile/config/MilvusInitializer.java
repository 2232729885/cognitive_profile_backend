package com.idata.profile.config;

import com.idata.profile.infra.milvus.MilvusVectorService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MilvusInitializer {

    private final MilvusVectorService milvusVectorService;

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        milvusVectorService.ensureAllCollections();
    }
}
