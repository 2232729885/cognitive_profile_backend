package com.idata.profile.controller;

import com.idata.profile.batch.profile.PersonProfileGenerationJob;
import com.idata.profile.batch.relation.AccountRelationBackfillJob;
import com.idata.profile.common.response.Result;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final PersonProfileGenerationJob personProfileGenerationJob;
    private final ChatClient chatClient;

    @PostMapping("/trigger-relation-backfill")
    public Map<String, Object> triggerRelationBackfill() {
        accountRelationBackfillJob.run();
        return Map.of(
                "status", "triggered",
                "job", "AccountRelationBackfillJob",
                "triggeredAt", OffsetDateTime.now().toString()
        );
    }

    @PostMapping("/trigger-profile-generation")
    public Result<String> triggerProfileGeneration() {
        personProfileGenerationJob.run();
        return Result.ok("triggered");
    }

    @PostMapping("/test-llm")
    public Result<String> testLlm(@RequestBody TestLlmRequest request) {
        String prompt = request != null ? request.getPrompt() : null;
        String content = chatClient.prompt()
                .user(prompt)
                .call()
                .content();
        return Result.ok(content);
    }

    @Data
    public static class TestLlmRequest {
        private String prompt;
    }
}
