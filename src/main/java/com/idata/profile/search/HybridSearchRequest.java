package com.idata.profile.search;

import lombok.Data;

@Data
public class HybridSearchRequest {
    private String queryText;
    private String platform;
    private String language;
    private Integer topK;
    private int page = 0;
    private int size = 10;
    private boolean enableEs = true;
    private boolean enableMilvus = true;
    private boolean enableNeo4j = false;
    private String imageUrl;
    private String targetModalities = "all";
    private Double semanticMinScore;
}
