package com.idata.profile.search;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class AlgorithmMediaContentSearchRequest {
    private String query;
    private String platform;
    private String language;
    private String contentType;
    private String authorPlatformUserId;
    private OffsetDateTime publishedFrom;
    private OffsetDateTime publishedTo;
    private Integer topK;
    private Integer size;
    private boolean enableKeyword = true;
    private boolean enableSemantic = true;
    private boolean enableMedia = true;
    private boolean enableGraph = false;
    private String targetModalities = "all";
    private Double semanticMinScore;
    private Double visualMinScore;
}
