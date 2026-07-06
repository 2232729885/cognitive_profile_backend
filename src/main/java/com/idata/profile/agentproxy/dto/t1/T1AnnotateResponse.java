package com.idata.profile.agentproxy.dto.t1;

import lombok.Data;

import java.util.List;

@Data
public class T1AnnotateResponse {

    /** 回传输入的语言 */
    private String language;

    /** 标注结果，嵌套对象，子字段由 annotationTypes 控制 */
    private Annotations annotations;

    /** 证据线索池 */
    private List<EvidenceClue> evidenceClues;

    /** 质量控制信息 */
    private QualityControl qualityControl;

    /** 本次标注整体置信度 0-1 */
    private Double confidence;

    /** T1 处理完成的 ISO 时间戳 */
    private String processedAt;

    /** 完整原始响应，存入 raw_records.t1_output */
    private String raw;

    @Data
    public static class Annotations {
        private List<String> topics;
        private List<String> keywords;
        private String summary;
        private LanguageStyle languageStyle;
        private Sentiment sentiment;
        private String eventType;
        private String contentPurpose;
        /** AI生成嫌疑：none/low/medium/high */
        private String aigcSuspicion;
        /** T2的实体提示列表 */
        private List<EntityHint> entitiesHint;
        /** 物体检测结果，annotate_image 时返回 */
        private List<DetectedObject> objects;
        /** 场景分类，如 outdoor/indoor/protest/military/text_heavy */
        private String scene;
        /** OCR 提取的文字，无文字时为 null */
        private String textOcr;

        @Data
        public static class DetectedObject {
            /** 物体标签，如 person/vehicle/weapon/flag/building */
            private String label;
            /** 置信度 0-1 */
            private Double confidence;
            /** 边界框 [x, y, width, height]，像素坐标 */
            private List<Integer> bbox;
        }

        @Data
        public static class LanguageStyle {
            /** formal/informal/mixed/unknown */
            private String formality;
            /** none/low/medium/high/unclear */
            private String emotionalIntensity;
        }

        @Data
        public static class Sentiment {
            /** positive/negative/neutral */
            private String label;
            /** -1.0 ~ 1.0 */
            private Double score;
        }

        @Data
        public static class EntityHint {
            private String text;
            /** person/organization/event/location/narrative/social_account/other/unknown */
            private String typeHint;
            private Span span;
            /** support/oppose/neutral */
            private String stance;
            private String emotionExpression;
            private String emotionIntensity;
            private List<String> evidenceIds;

            @Data
            public static class Span {
                private Integer start;
                private Integer end;
            }
        }
    }

    @Data
    public static class EvidenceClue {
        private String evidenceId;
        /** text_span/image_region/video_segment */
        private String evidenceType;
        private String rawContent;
        private Object span;
        private Double confidence;
    }

    @Data
    public static class QualityControl {
        /** success/failed/partial */
        private String autoLabelStatus;
        private Boolean needHumanReview;
        private String reviewReason;
        private String schemaVersion;
        private String modelVersion;
    }
}
