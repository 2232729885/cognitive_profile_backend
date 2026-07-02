package com.idata.profile.controller;

import com.idata.profile.common.response.Result;
import com.idata.profile.infra.neo4j.Neo4jGraphService;
import com.idata.profile.search.HybridSearchRequest;
import com.idata.profile.search.SearchResult;
import com.idata.profile.search.SearchService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;
    private final Neo4jGraphService neo4jGraphService;

    @PostMapping("/text")
    public Result<SearchResult> searchByText(@RequestBody TextSearchRequest request) {
        return Result.ok(searchService.searchByText(
                request.getKeyword(),
                request.getPlatform(),
                request.getLanguage(),
                request.getPage(),
                request.getSize()));
    }

    @PostMapping("/semantic")
    public Result<SearchResult> searchBySemantic(@RequestBody SemanticSearchRequest request) {
        return Result.ok(searchService.searchBySemantic(
                request.getQueryText(),
                request.getPlatform(),
                request.getLanguage(),
                request.getTopK()));
    }

    @PostMapping("/hybrid")
    public Result<SearchResult> searchHybrid(@RequestBody HybridSearchRequest request) {
        return Result.ok(searchService.searchHybrid(request));
    }

    @GetMapping("/graph/{label}/{nodeId}")
    public Result<Map<String, Object>> findTwoHopGraph(@PathVariable String label,
                                                       @PathVariable String nodeId) {
        return Result.ok(neo4jGraphService.findTwoHopGraph(nodeId, label));
    }

    @GetMapping("/path")
    public Result<Map<String, Object>> findShortestPath(@RequestParam String fromId,
                                                        @RequestParam String toId) {
        return Result.ok(neo4jGraphService.findShortestPath(fromId, toId));
    }

    /**
     * Entity fuzzy search.
     * Valid label values: Person/Organization/Event/Location/Narrative/SocialAccount/MediaContent.
     * If label is empty, all seven node types are searched.
     */
    @GetMapping("/entities")
    public Result<List<Map<String, Object>>> searchEntities(@RequestParam String keyword,
                                                            @RequestParam(required = false) String label,
                                                            @RequestParam(defaultValue = "10") int limit) {
        return Result.ok(neo4jGraphService.searchNodesByName(keyword, label, limit));
    }

    @Data
    public static class TextSearchRequest {
        private String keyword;
        private String platform;
        private String language;
        private int page = 0;
        private int size = 20;
    }

    @Data
    public static class SemanticSearchRequest {
        private String queryText;
        private String platform;
        private String language;
        private int topK = 20;
    }
}
