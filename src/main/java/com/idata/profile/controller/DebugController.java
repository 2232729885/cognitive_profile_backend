package com.idata.profile.controller;

import com.idata.profile.batch.relation.AccountRelationBackfillJob;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Map;

@RestController
@RequestMapping("/debug")
@Profile("mock")
@RequiredArgsConstructor
public class DebugController {

    private final AccountRelationBackfillJob accountRelationBackfillJob;

    @PostMapping("/trigger-relation-backfill")
    public Map<String, Object> triggerRelationBackfill() {
        accountRelationBackfillJob.run();
        return Map.of(
                "status", "triggered",
                "job", "AccountRelationBackfillJob",
                "triggeredAt", OffsetDateTime.now().toString()
        );
    }
}
