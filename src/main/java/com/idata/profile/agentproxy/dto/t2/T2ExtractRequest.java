package com.idata.profile.agentproxy.dto.t2;

import lombok.Data;

@Data
public class T2ExtractRequest {
    /** 原始文本（RZDK字段名为 text）*/
    private String text;

    /**
     * T1 的完整标注结果（RZDK字段名为 annotation）。
     * 直接传 T1AnnotateResponse 对应的 annotations 对象（含 entities_hint、sentiment 等）。
     */
    private Object annotation;

    /** 来源信息 */
    private SourceInfo sourceInfo;

    private String[] hashtags;
    private String[] mentions;
    private String parentContentId;
    private String repostOfContentId;
    private String quotedContentId;

    @Data
    public static class SourceInfo {
        private String platformId;
        private String contentUrl;
        private String publishTime;
        private String authorHandle;
    }
}
