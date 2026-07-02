package com.idata.profile.search;

import com.idata.profile.entity.content.MediaContent;
import lombok.Data;

import java.util.List;

@Data
public class SearchResult {
    private List<MediaContent> items;
    private int total;
    private String searchType;
    private long durationMs;
}
