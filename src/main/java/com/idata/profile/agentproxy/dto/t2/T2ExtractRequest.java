package com.idata.profile.agentproxy.dto.t2;

import lombok.Data;

@Data
public class T2ExtractRequest {
    private String contentId;
    private String text;
    private Object annotation;
    private SourceInfo source;
    private String language;

    @Data
    public static class SourceInfo {
        private String platform;
        private String url;
        private String publishedAt;
        private String authorHandle;
        private String[] hashtags;
        private String[] mentions;
        private String parentContentId;
        private String repostOfContentId;
        private String quotedContentId;
    }
}
