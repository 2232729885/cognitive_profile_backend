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
        /** 维度9：事件类型/议题归属细化 */
        private String eventType;
        /** 维度7：内容目的 */
        private String contentPurpose;
        /**
         * AI生成嫌疑：none/low/medium/high
         *
         * @deprecated v1.1 起并入 {@link Risk#aigcSuspicion}，此字段仅为兼容旧数据保留
         */
        @Deprecated
        private String aigcSuspicion;
        /** 维度8：风险等级（v1.1 新增，替代原 aigcSuspicion 独立字段） */
        private Risk risk;
        /** 维度1：意识形态（v1.1 新增，无明显倾向时为 null） */
        private Ideology ideology;
        /** 维度2：内容级核心立场（v1.1 新增）：support/oppose/neutral/mixed/unknown */
        private String overallStance;
        /** 维度4：事件热度（v1.1 新增）：low/medium/high/breaking/unknown */
        private String eventHeat;
        /** 维度5：账户类别（v1.1 新增）：official/media/journalist/individual/kol/organization/bot/unknown */
        private String accountTypeHint;
        /** 维度10：BEND 叙事操纵手法（v1.1 新增） */
        private List<BendTactic> bendTactics;
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
            /**
             * 风格标签（多选，v1.1 新增）：
             * neutral/rational/critical/emotional/aggressive/sarcastic/slogan/sensational/mobilization/panic_inducing
             */
            private List<String> styleTags;
        }

        @Data
        public static class Sentiment {
            /** positive/negative/neutral/mixed */
            private String label;
            /** -1.0 ~ 1.0 或 0.0~1.0（按算法组实际口径） */
            private Double score;
            /** 主要情绪类型（v1.1 新增）：anger/fear/sadness/joy/pride/anxiety/calm/contempt/null */
            private String primaryEmotion;
            /** 情绪极性（v1.1 新增，与 label 对应）：positive/negative/neutral/mixed */
            private String emotionPolarity;
            /** 情绪强度（v1.1 新增）：low/medium/high */
            private String emotionIntensity;
        }

        /** 维度1：意识形态（v1.1 新增） */
        @Data
        public static class Ideology {
            /** nationalist/conservative/liberal/religious/anti_establishment/pro_west/anti_west/neutral/unclear */
            private String label;
            /** weak/moderate/strong */
            private String intensity;
            /** 支撑证据片段 */
            private String evidence;
        }

        /** 维度8：风险等级（v1.1 新增，综合维度） */
        @Data
        public static class Risk {
            /** low/medium/high */
            private String level;
            /** 风险类型（多选）：misinformation/rumor/polarization/panic_inducing/mobilization/aigc_deception */
            private List<String> types;
            /** 触发风险的原文证据 */
            private String evidence;
            /** AIGC 嫌疑（v1.1 合并进 risk）：none/low/medium/high */
            private String aigcSuspicion;
        }

        /** 维度10：BEND 叙事操纵手法条目（v1.1 新增） */
        @Data
        public static class BendTactic {
            /** Engage/Explain/Excite/Enhance/Dismiss/Distort/Dismay/Distract */
            private String tactic;
            /** 置信度 0-1 */
            private Double confidence;
            /** 对应证据片段 */
            private String evidence;
            /** 判定理由 */
            private String reason;
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
