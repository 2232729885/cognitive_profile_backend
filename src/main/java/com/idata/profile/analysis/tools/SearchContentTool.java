package com.idata.profile.analysis.tools;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.idata.profile.analysis.orchestrator.CoordinatorAgentService;
import com.idata.profile.analysis.workflow.WorkflowTaskService;
import com.idata.profile.entity.content.MediaContent;
import com.idata.profile.search.HybridSearchRequest;
import com.idata.profile.search.SearchResult;
import com.idata.profile.search.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Component("searchContentTool")
@RequiredArgsConstructor
public class SearchContentTool implements Function<SearchContentTool.Request, SearchContentTool.Response> {

    private final SearchService searchService;
    private final ApplicationContext applicationContext;

    public record Request(
            @JsonProperty(required = true)
            @JsonPropertyDescription("用户的分析意图或关键词，如'伊朗核问题相关内容'")
            String query,

            @JsonPropertyDescription("平台过滤，如x/telegram/youtube，null表示不过滤")
            String platform,

            @JsonPropertyDescription("语言过滤，如zh/en/fa，null表示不过滤")
            String language,

            @JsonPropertyDescription("返回结果数量，默认20")
            Integer topK
    ) {
    }

    public record Response(
            List<ContentSummary> contents,
            int total,
            long durationMs
    ) {
    }

    public record ContentSummary(
            String contentId,
            String platform,
            String language,
            String bodyText,
            String authorPlatformUserId,
            String publishedAt,
            String topicCategory,
            String sentimentLabel
    ) {
    }

    @Override
    public Response apply(Request request) {
        long startedAt = System.currentTimeMillis();
        String taskId = currentTaskId();
        sendStarted(taskId, "search_content", request, startedAt);

        try {
            HybridSearchRequest searchRequest = new HybridSearchRequest();
            searchRequest.setQueryText(request.query());
            searchRequest.setPlatform(request.platform());
            searchRequest.setLanguage(request.language());
            searchRequest.setTopK(request.topK() != null && request.topK() > 0 ? request.topK() : 20);
            searchRequest.setEnableEs(true);
            searchRequest.setEnableMilvus(true);
            searchRequest.setEnableNeo4j(true);

            SearchResult result = searchService.searchHybrid(searchRequest);
            Response response = new Response(
                    result.getItems() == null ? List.of() : result.getItems().stream().map(this::toSummary).toList(),
                    result.getTotal(),
                    result.getDurationMs()
            );
            sendDone(taskId, "search_content", request, response, startedAt);
            return response;
        } catch (RuntimeException e) {
            sendFailed(taskId, "search_content", request, e, startedAt);
            throw e;
        }
    }

    private ContentSummary toSummary(MediaContent content) {
        return new ContentSummary(
                stringValue(content.getId()),
                content.getPlatform(),
                content.getLanguage(),
                truncate(content.getBodyText(), 200),
                content.getAuthorPlatformUserId(),
                stringValue(content.getPublishedAt()),
                content.getTopicCategory(),
                content.getSentimentLabel()
        );
    }

    private String currentTaskId() {
        return applicationContext.getBean(CoordinatorAgentService.class).currentTaskId();
    }

    private void sendStarted(String taskId, String stepName, Object input, long startedAt) {
        if (taskId == null) {
            return;
        }
        coordinator().sendSseEvent(taskId, "tool_started", Map.of("stepName", stepName, "input", input));
        workflow().appendStep(UUID.fromString(taskId),
                new WorkflowTaskService.StepRecord(stepName, "running", startedAt, null, input, null, null));
    }

    private void sendDone(String taskId, String stepName, Object input, Object output, long startedAt) {
        if (taskId == null) {
            return;
        }
        long doneAt = System.currentTimeMillis();
        coordinator().sendSseEvent(taskId, "tool_completed",
                Map.of("stepName", stepName, "output", output, "durationMs", doneAt - startedAt));
        workflow().appendStep(UUID.fromString(taskId),
                new WorkflowTaskService.StepRecord(stepName, "done", startedAt, doneAt, input, output, null));
    }

    private void sendFailed(String taskId, String stepName, Object input, Exception e, long startedAt) {
        if (taskId == null) {
            return;
        }
        long doneAt = System.currentTimeMillis();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stepName", stepName);
        data.put("error", e.getMessage());
        coordinator().sendSseEvent(taskId, "tool_failed", data);
        workflow().appendStep(UUID.fromString(taskId),
                new WorkflowTaskService.StepRecord(stepName, "failed", startedAt, doneAt, input, null, e.getMessage()));
    }

    private CoordinatorAgentService coordinator() {
        return applicationContext.getBean(CoordinatorAgentService.class);
    }

    private WorkflowTaskService workflow() {
        return applicationContext.getBean(WorkflowTaskService.class);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime time) {
            return time.toString();
        }
        return value.toString();
    }
}
