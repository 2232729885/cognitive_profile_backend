package com.idata.profile.agentproxy.dto.t1;

import lombok.Data;

import java.util.List;

@Data
public class T1AnnotateRequest {
    /** 待标注文本（RZDK字段名为 text，不是 bodyText）*/
    private String text;

    /** 语言代码，默认 zh */
    private String language;

    /**
     * 标注类型，控制T1返回哪些维度。
     * 可选值：topics/keywords/summary/language_style/sentiment/
     *         event_type/content_purpose/aigc_suspicion/entities_hint
     * 不传则默认全部9项。
     */
    private List<String> annotationTypes;

    /** 图像 URL，图像标注时必填（MinIO 可访问地址）*/
    private String imageUrl;

    /** 图像 base64，imageUrl 不可用时传此字段（可选）*/
    private String imageData;

    /** 内容上下文，辅助提升标注质量（v1.1 新增，可选） */
    private Context context;

    @Data
    public static class Context {
        /** 内容ID，用于日志追踪 */
        private String docId;
        /** 来源平台，辅助账户类别判断 */
        private String platform;
        /** post/comment/reply/article */
        private String contentType;
        private String authorHandle;
        private String publishedAt;
        /** 话题标签，辅助话题判断 */
        private List<String> hashtags;
        private String parentContentId;
    }
}
