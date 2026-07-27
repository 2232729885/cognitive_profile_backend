package com.idata.profile.controller;

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
    public AlgorithmMediaContentSearchResponse searchMediaContents(
            @RequestBody AlgorithmMediaContentSearchRequest request) {
        return searchService.searchMediaContentsForAlgorithm(request);
    }
}
