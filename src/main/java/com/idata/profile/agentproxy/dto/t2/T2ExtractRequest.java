package com.idata.profile.agentproxy.dto.t2;

import lombok.Data;

@Data
public class T2ExtractRequest {
    private String docId;
    private String text;
    private Object annotation;
    private SourceInfo source;
    private String language;

    /**
     * @deprecated Use {@link #source}. Kept only for older internal agent prompt code.
     */
    @Deprecated
    public SourceInfo getSourceInfo() {
        return source;
    }

    /**
     * @deprecated Use {@link SourceInfo#hashtags}. Kept only for older internal agent prompt code.
     */
    @Deprecated
    public String[] getHashtags() {
        return source != null ? source.getHashtags() : null;
    }

    @Data
    public static class SourceInfo {
        private String platformId;
        private String contentUrl;
        private String publishTime;
        private String authorHandle;
        private String[] hashtags;
        private String[] mentions;
        private String parentContentId;
        private String repostOfContentId;
        private String quotedContentId;
    }
}
