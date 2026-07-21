package com.idata.profile.agentproxy.dto.t1;

import lombok.Data;

import java.util.List;

@Data
public class T1AnnotateRequest {
    /** 标题，新闻类内容可能有单独标题，社交内容通常为空 */
    private String title;

    /** 正文文本，可以为空（纯图片/视频内容） */
    private String text;

    /** 语言代码 */
    private String language;

    /** 图片/视频列表，可以为空（纯文本内容），也可以同时有多张图和多个视频 */
    private List<MediaItem> medias = List.of();

    /** 内容上下文，辅助提升标注质量 */
    private Context context;

    @Data
    public static class MediaItem {
        /** 对应 media_assets.id，用于结果里 evidence_clues.media_id 回填定位 */
        private String id;

        /** MinIO 可访问地址 */
        private String url;

        /** image | video */
        private String mediaType;
    }

    @Data
    public static class Context {
        private String contentId;
        private String platform;
        private String url;
        /** post/comment/reply/article */
        private String contentType;
        private String authorHandle;
        private String publishedAt;
        private List<String> hashtags;
        private Long likeCount;
        private Long commentCount;
        private Long shareCount;
        private Long repostCount;
        private Long viewCount;
        private String parentContentId;
    }
}
