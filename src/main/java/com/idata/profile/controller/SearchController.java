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

/**
 * F6 检索与知识图谱分析。
 */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;
    private final Neo4jGraphService neo4jGraphService;

    /**
     * 关键词全文检索，只查 ES。
     * 示例：{"keyword":"选举干预","platform":"x","language":"zh","page":0,"size":20}
     */
    @PostMapping("/text")
    public Result<SearchResult> searchByText(@RequestBody TextSearchRequest request) {
        return Result.ok(searchService.searchByText(
                request.getKeyword(),
                request.getPlatform(),
                request.getLanguage(),
                request.getPage(),
                request.getSize()));
    }

    /**
     * 语义相似检索，调 T4 向量化后查 Milvus。
     * 示例：{"queryText":"外部势力操纵舆论","platform":null,"language":null,"topK":20}
     */
    @PostMapping("/semantic")
    public Result<SearchResult> searchBySemantic(@RequestBody SemanticSearchRequest request) {
        return Result.ok(searchService.searchBySemantic(
                request.getQueryText(),
                request.getPlatform(),
                request.getLanguage(),
                request.getTopK()));
    }

    /**
     * 三路融合检索，ES + Milvus + Neo4j。
     * 示例：{"queryText":"外部势力操纵舆论","platform":"x","language":"zh","topK":20,"enableEs":true,"enableMilvus":true,"enableNeo4j":false}
     */
    @PostMapping("/hybrid")
    public Result<SearchResult> searchHybrid(@RequestBody HybridSearchRequest request) {
        return Result.ok(searchService.searchHybrid(request));
    }

    /**
     * 2跳知识图谱查询。
     * 示例：GET /api/search/graph/Person/00000000-0000-0000-0000-000000000000
     */
    @GetMapping("/graph/{label}/{nodeId}")
    public Result<Map<String, Object>> findTwoHopGraph(@PathVariable String label,
                                                       @PathVariable String nodeId) {
        return Result.ok(neo4jGraphService.findTwoHopGraph(nodeId, label));
    }

    /**
     * 两节点最短路径查询。
     * 示例：GET /api/search/path?fromId=00000000-0000-0000-0000-000000000000&toId=11111111-1111-1111-1111-111111111111
     */
    @GetMapping("/path")
    public Result<Map<String, Object>> findShortestPath(@RequestParam String fromId,
                                                        @RequestParam String toId) {
        return Result.ok(neo4jGraphService.findShortestPath(fromId, toId));
    }

    /**
     * 实体名称模糊搜索。
     * 示例：GET /api/search/entities?keyword=张三&label=Person&limit=10
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
