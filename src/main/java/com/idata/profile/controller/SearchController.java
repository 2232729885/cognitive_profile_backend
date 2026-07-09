package com.idata.profile.controller;

import com.idata.profile.common.response.Result;
import com.idata.profile.infra.minio.MinioStorageService;
import com.idata.profile.infra.neo4j.Neo4jGraphService;
import com.idata.profile.search.HybridSearchRequest;
import com.idata.profile.search.SearchResult;
import com.idata.profile.search.SearchService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;
    private final Neo4jGraphService neo4jGraphService;
    private final MinioStorageService minioStorageService;

    @Value("${minio.endpoint}")
    private String minioEndpoint;

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

    @PostMapping("/image")
    public Result<SearchResult> searchByImage(@RequestBody ImageSearchRequest request) {
        if (request == null || !hasText(request.getImageUrl())) {
            return Result.fail("INVALID_PARAM", "imageUrl不能为空");
        }
        return Result.ok(searchService.searchByImage(
                request.getImageUrl(), request.getTargetModalities(), request.getTopK()));
    }

    @PostMapping("/image/base64")
    public Result<SearchResult> searchByBase64Image(@RequestBody ImageBase64SearchRequest request) {
        if (request == null || !hasText(request.getImageBase64())) {
            return Result.fail("INVALID_PARAM", "imageBase64不能为空");
        }
        try {
            ParsedBase64Image image = parseBase64Image(request.getImageBase64());
            String key = "search-temp/" + UUID.randomUUID() + "." + extensionForContentType(image.contentType());
            String objectPath = minioStorageService.upload("media-assets", key, image.bytes(), image.contentType());
            String imageUrl = buildMinioUrl(objectPath);
            return Result.ok(searchService.searchByImage(
                    imageUrl, request.getTargetModalities(), request.getTopK()));
        } catch (IllegalArgumentException e) {
            return Result.fail("INVALID_FORMAT", "图片base64格式不合法");
        }
    }

    @PostMapping("/image/upload")
    public Result<SearchResult> searchByUploadedImage(@RequestParam("file") MultipartFile file,
                                                      @RequestParam(defaultValue = "all") String targetModalities,
                                                      @RequestParam(defaultValue = "20") int topK) throws IOException {
        if (file == null || file.isEmpty()) {
            return Result.fail("INVALID_FILE", "上传文件不能为空");
        }
        if (!hasText(file.getContentType()) || !file.getContentType().startsWith("image/")) {
            return Result.fail("INVALID_FORMAT", "只支持图片文件（jpg/png/gif/webp）");
        }

        String key = "search-temp/" + UUID.randomUUID() + "." + extensionOf(file.getOriginalFilename(), file.getContentType());
        String objectPath = minioStorageService.upload("media-assets", key, file.getBytes(), file.getContentType());
        String imageUrl = buildMinioUrl(objectPath);
        return Result.ok(searchService.searchByImage(imageUrl, targetModalities, topK));
    }

    @GetMapping("/graph/overview")
    public Result<Map<String, Object>> getOverviewGraph(@RequestParam(defaultValue = "300") int limit) {
        return Result.ok(neo4jGraphService.getOverviewGraph(Math.min(limit, 500)));
    }

    @GetMapping("/graph/{label}/{nodeId}")
    public Result<Map<String, Object>> getNodeGraph(@PathVariable String label,
                                                    @PathVariable String nodeId,
                                                    @RequestParam(defaultValue = "1") int hops) {
        return Result.ok(neo4jGraphService.findHopGraph(nodeId, label, Math.min(hops, 2)));
    }

    @GetMapping("/path")
    public Result<Map<String, Object>> findShortestPath(@RequestParam String fromId,
                                                        @RequestParam String toId) {
        return Result.ok(neo4jGraphService.findShortestPath(fromId, toId));
    }

    /**
     * Entity fuzzy search.
     * Valid label values: Person/Organization/Event/Location/Narrative/SocialAccount/MediaContent.
     * If label is empty, all supported node types are searched.
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

    @Data
    public static class ImageSearchRequest {
        private String imageUrl;
        private String targetModalities = "all";
        private int topK = 20;
    }

    @Data
    public static class ImageBase64SearchRequest {
        private String imageBase64;
        private String targetModalities = "all";
        private int topK = 20;
    }

    private ParsedBase64Image parseBase64Image(String value) {
        String contentType = "image/jpeg";
        String payload = value.trim();
        if (payload.startsWith("data:")) {
            int commaIndex = payload.indexOf(',');
            if (commaIndex < 0) {
                throw new IllegalArgumentException("Missing base64 comma separator");
            }
            String prefix = payload.substring(5, commaIndex);
            String[] parts = prefix.split(";");
            if (parts.length > 0 && hasText(parts[0])) {
                contentType = parts[0];
            }
            payload = payload.substring(commaIndex + 1);
        }
        byte[] bytes = Base64.getDecoder().decode(payload.replaceAll("\\s", ""));
        if (!contentType.startsWith("image/")) {
            throw new IllegalArgumentException("Unsupported content type");
        }
        return new ParsedBase64Image(bytes, contentType);
    }

    private String buildMinioUrl(String objectPath) {
        return trimTrailingSlash(minioEndpoint) + "/" + objectPath;
    }

    private String extensionOf(String filename, String contentType) {
        if (hasText(filename)) {
            int dotIndex = filename.lastIndexOf('.');
            if (dotIndex >= 0 && dotIndex < filename.length() - 1) {
                return filename.substring(dotIndex + 1).toLowerCase();
            }
        }
        return extensionForContentType(contentType);
    }

    private String extensionForContentType(String contentType) {
        if ("image/png".equalsIgnoreCase(contentType)) {
            return "png";
        }
        if ("image/gif".equalsIgnoreCase(contentType)) {
            return "gif";
        }
        if ("image/webp".equalsIgnoreCase(contentType)) {
            return "webp";
        }
        return "jpg";
    }

    private String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private record ParsedBase64Image(byte[] bytes, String contentType) {
    }
}
