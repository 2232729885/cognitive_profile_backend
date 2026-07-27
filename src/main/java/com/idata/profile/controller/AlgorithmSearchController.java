package com.idata.profile.controller;

import com.idata.profile.common.response.Result;
import com.idata.profile.search.AlgorithmMediaContentSearchRequest;
import com.idata.profile.search.AlgorithmMediaContentSearchResponse;
import com.idata.profile.search.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/algorithm")
@RequiredArgsConstructor
public class AlgorithmSearchController {

    private final SearchService searchService;

    @PostMapping("/media-contents/search")
    public Result<AlgorithmMediaContentSearchResponse> searchMediaContents(
            @RequestBody AlgorithmMediaContentSearchRequest request) {
        if (request == null || !hasText(request.getQuery())) {
            return Result.fail("INVALID_PARAM", "query不能为空");
        }
        return Result.ok(searchService.searchMediaContentsForAlgorithm(request));
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
