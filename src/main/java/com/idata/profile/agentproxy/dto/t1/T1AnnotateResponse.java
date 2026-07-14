package com.idata.profile.agentproxy.dto.t1;

import lombok.Data;

import java.util.List;

@Data
public class T1AnnotateResponse {

    /** 固定为 "t1_annotation_v0.6" */
    private String schemaVersion;

    /** 输入引用信息 */
    private InputReference inputReference;

    /** 回传输入的语言 */
    private String language;

    /** AIGC 检测结果，覆盖文本/图像/视频/多模态一致性 */
    private AigcDetection aigcDetection;

    /** 自动标注结果（高价值主观 + 基础客观） */
    private Annotations annotations;

    /** 原始证据池 */
    private List<EvidenceClue> evidenceClues;

    /** 质量控制 */
    private QualityControl qualityControl;

    /** T1 对整条输出的总体置信度 */
    private Double overallConfidence;

    /** T1 处理完成时间，ISO 8601 */
    private String processedAt;

    // ==================== input_reference ====================

    @Data
    public static class InputReference {
        private String contentId;
        /** post | comment | reply | article */
        private String contentType;
        /** text | image | video | text_image | text_video | image_video | text_image_video */
        private String modalityCombination;
        private String platform;
        private String url;
        private String authorId;
        private String createdAt;
    }

    // ==================== aigc_detection ====================

    @Data
    public static class AigcDetection {
        /** ai_generated | human_generated | mixed | suspicious | unclear */
        private String overallAigcLabel;
        private Double overallAigcScore;
        private TextAigcDetection textAigcDetection;
        private ImageAigcDetection imageAigcDetection;
        private VideoAigcDetection videoAigcDetection;
        private MultimodalAigcDetection multimodalAigcDetection;
        private Double aigcDetectionConfidence;

        @Data
        public static class TextAigcDetection {
            /** ai_generated | human_generated | mixed | suspicious | unclear | not_applicable */
            private String textAigcLabel;
            private Double textAigcScore;
            /** ai_self_disclosure/template_like_structure/generic_over_polished/repetitive_phrasing/
             *  unnatural_transition/instruction_following_trace/mixed_style/none/unclear */
            private List<String> textAigcSignalLabels;
            private Double textAigcConfidence;
            private List<String> evidenceIds;
        }

        @Data
        public static class ImageAigcDetection {
            /** ai_generated | human_generated | edited_or_manipulated | mixed | suspicious | unclear | not_applicable */
            private String imageAigcLabel;
            private Double imageAigcScore;
            /** visual_artifact/face_inconsistency/hand_or_body_anomaly/text_rendering_anomaly/
             *  lighting_shadow_inconsistency/background_distortion/object_boundary_anomaly/
             *  metadata_anomaly/deepfake_signal/local_manipulation_signal/none/unclear */
            private List<String> imageAigcSignalLabels;
            private Double imageAigcConfidence;
            private List<String> evidenceIds;
        }

        @Data
        public static class VideoAigcDetection {
            /** ai_generated | human_generated | deepfake | edited_or_manipulated | mixed | suspicious | unclear | not_applicable */
            private String videoAigcLabel;
            private Double videoAigcScore;
            /** deepfake_signal/face_swap_signal/lip_sync_inconsistency/audio_visual_mismatch/
             *  voice_synthesis_signal/temporal_inconsistency/frame_artifact/motion_anomaly/
             *  lighting_shadow_inconsistency/background_distortion/scene_boundary_anomaly/
             *  metadata_anomaly/local_manipulation_signal/none/unclear */
            private List<String> videoAigcSignalLabels;
            private Double videoAigcConfidence;
            private List<String> evidenceIds;
        }

        @Data
        public static class MultimodalAigcDetection {
            /** consistent | inconsistent | mixed_generated | suspicious | unclear | not_applicable */
            private String multimodalAigcLabel;
            /** text_image/text_video/image_video/image_ocr/video_audio/video_subtitle/text_image_video/other，
             *  可以多选，本次实际检查过的模态组合 */
            private List<String> checkedModalityPairs;
            /** text_image_mismatch/text_video_mismatch/image_video_mismatch/image_ocr_mismatch/
             *  audio_visual_mismatch/subtitle_visual_mismatch/caption_context_mismatch/
             *  cross_modal_source_mismatch/mixed_generation_signal/none/unclear */
            private List<String> multimodalSignalLabels;
            private Double multimodalAigcConfidence;
            private List<String> evidenceIds;
        }
    }

    // ==================== annotations ====================

    @Data
    public static class Annotations {
        private HighValueSubjective highValueSubjective;
        private BasicObjective basicObjective;

        @Data
        public static class HighValueSubjective {
            private Ideology ideology;
            private CoreStance coreStance;
            private OpinionEmotion opinionEmotion;
            private LanguageStyle languageStyle;
            private ManipulationMethod manipulationMethod;
            private RiskLevel riskLevel;

            /** 维度1：意识形态倾向 */
            @Data
            public static class Ideology {
                /** left_leaning/right_leaning/liberal/conservative/nationalist/populist/
                 *  pro_government/anti_government/pro_western/anti_western/neutral/
                 *  not_obvious/mixed/unclear/other */
                private String ideologyLabel;
                private Double ideologyConfidence;
                private List<String> evidenceIds;
            }

            /** 维度2：内容级总体立场 */
            @Data
            public static class CoreStance {
                private StanceTarget stanceTarget;
                /** support | oppose | neutral | mixed | unclear */
                private String stanceLabel;
                /** weak | medium | strong | unclear */
                private String stanceStrength;
                private Double coreStanceConfidence;
                private List<String> evidenceIds;

                @Data
                public static class StanceTarget {
                    /** event/issue/policy/action/person/organization/country_or_region/
                     *  ideology_or_value/other/unclear */
                    private String targetType;
                    private String targetText;
                }
            }

            /** 维度3：观点情绪 */
            @Data
            public static class OpinionEmotion {
                /** positive | negative | neutral | mixed | unclear */
                private String sentimentPolarity;
                /** anger/fear/sadness/anxiety/disgust/contempt/joy/hope/sympathy/surprise/none/unclear */
                private List<String> emotionLabels;
                /** low | medium | high | unclear | not_applicable */
                private String emotionIntensity;
                private Double opinionEmotionConfidence;
                private List<String> evidenceIds;
            }

            /** 维度4：语言表达方式 */
            @Data
            public static class LanguageStyle {
                /** neutral/aggressive/sarcastic/mocking/alarmist/threatening/sensationalized/
                 *  emotional/conspiratorial/accusatory/slogan_like/rhetorical_questioning/
                 *  rational_analytical/unclear/not_applicable */
                private List<String> styleLabels;
                private Double languageStyleConfidence;
                private List<String> evidenceIds;
            }

            /** 维度5：BEND 叙事操纵手法（原 bendTactics，v0.6 改成单对象+统一置信度） */
            @Data
            public static class ManipulationMethod {
                /** engage | explain | excite | enhance | dismiss | distort | dismay | distract，可多选，可为空数组 */
                private List<String> methodLabels;
                private Double manipulationMethodConfidence;
                private List<String> evidenceIds;
            }

            /** 维度6：风险等级 */
            @Data
            public static class RiskLevel {
                /** none | low | medium | high | severe | unclear */
                private String riskLabel;
                /** misinformation/rumor/polarization/hostility/panic_amplification/
                 *  mobilization_risk/reputation_attack/manipulation/none/unclear */
                private List<String> riskTypes;
                private Double riskLevelConfidence;
                private List<String> evidenceIds;
            }
        }

        @Data
        public static class BasicObjective {
            private TopicTags topicTags;
            private List<EntityHint> entitiesHint;
            private List<Keyword> keywords;
            private Summary summary;
            private TopicType topicType;

            /** 维度7：话题标签（只输出一个主要领域，不再细分子话题） */
            @Data
            public static class TopicTags {
                /** politics/military/economy_finance/technology_cyber/public_health/
                 *  social_livelihood/ethnic_religious/energy_environment/disaster_accident/
                 *  crime_public_safety/culture_education/migration_refugee/other/unclear */
                private String primaryDomain;
                private Double topicTagsConfidence;
                private List<String> evidenceIds;
            }

            /** 维度8：实体线索（只提供线索，不替代T2正式抽取） */
            @Data
            public static class EntityHint {
                private String entityHintId;
                private String text;
                /** persons/organizations/events/locations/media_contents/social_accounts/narratives/others/unknown */
                private String typeHint;
                private Double entityHintConfidence;
                private List<String> evidenceIds;
            }

            /** 维度9：关键词 */
            @Data
            public static class Keyword {
                private String keywordText;
                private Double keywordConfidence;
                private List<String> evidenceIds;
            }

            /** 维度10：摘要（依据整条多模态输入生成，不设 evidenceIds） */
            @Data
            public static class Summary {
                private String summaryText;
                private Double summaryConfidence;
            }

            /** 维度11：话题类型（比topicTags.primaryDomain更细粒度的话题分类） */
            @Data
            public static class TopicType {
                /** military_conflict/diplomatic_dispute/policy_announcement/election_campaign/
                 *  protest_demonstration/economic_sanction/cyber_incident/public_health_event/
                 *  disaster_accident/crime_public_safety/social_livelihood_event/
                 *  public_opinion_event/other/unclear/not_applicable */
                private String topicTypeLabel;
                private Double topicTypeConfidence;
                private List<String> evidenceIds;
            }
        }
    }

    // ==================== evidence_clues ====================

    @Data
    public static class EvidenceClue {
        private String evidenceId;
        /** text_span | image_region | video_segment | video_frame_region | metadata | model_signal */
        private String evidenceType;
        /** text | image | video | audio | ocr | subtitle | metadata | aigc_detector */
        private String source;
        private String evidenceText;
        private List<Integer> span;
        private String mediaId;
        private Region region;
        private TimeRange timeRange;
        private Object metadataSnapshot;
        private Object modelSignal;

        @Data
        public static class Region {
            private Double x;
            private Double y;
            private Double width;
            private Double height;
        }

        @Data
        public static class TimeRange {
            private Double start;
            private Double end;
        }
    }

    // ==================== quality_control ====================

    @Data
    public static class QualityControl {
        private Boolean needHumanReview;
        /** low_confidence/insufficient_context/conflicting_signals/multimodal_inconsistency/
         *  aigc_suspicious/high_risk_content/missing_metadata/module_failure/too_short_input/
         *  manual_policy_required/other */
        private List<String> reviewReasons;
        /** aigcDetection/textAigcDetection/imageAigcDetection/videoAigcDetection/multimodalAigcDetection/
         *  ideology/coreStance/opinionEmotion/languageStyle/manipulationMethod/riskLevel/
         *  topicTags/entitiesHint/keywords/summary/topicType/other */
        private List<String> failedModules;
    }
}
