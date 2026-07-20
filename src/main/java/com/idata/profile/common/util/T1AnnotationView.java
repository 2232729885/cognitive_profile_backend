package com.idata.profile.common.util;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 把 media_contents.t1_annotation 这段完整JSON按需取出常用字段，
 * 供 T4IndexingStep/SearchContentTool/ContentPropagationBackfillJob 等
 * 需要展平字段的场景复用，避免各处各写一份JSON路径解析逻辑。
 */
public final class T1AnnotationView {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final JsonNode root;

    private T1AnnotationView(JsonNode root) {
        this.root = root;
    }

    public static T1AnnotationView parse(String t1AnnotationJson) {
        if (t1AnnotationJson == null || t1AnnotationJson.isBlank()) {
            return new T1AnnotationView(null);
        }
        try {
            return new T1AnnotationView(OBJECT_MAPPER.readTree(t1AnnotationJson));
        } catch (Exception e) {
            return new T1AnnotationView(null);
        }
    }

    public String topicCategory() {
        return textAt("annotations", "basicObjective", "topicTags", "primaryDomain");
    }

    public String sentimentLabel() {
        return textAt("annotations", "highValueSubjective", "opinionEmotion", "sentimentPolarity");
    }

    public String stanceLabel() {
        return textAt("annotations", "highValueSubjective", "coreStance", "stanceLabel");
    }

    public Double aigcScore() {
        JsonNode node = path("aigcDetection", "overallAigcScore");
        return node != null && node.isNumber() ? node.asDouble() : null;
    }

    public String aigcType() {
        return textAt("aigcDetection", "overallAigcLabel");
    }

    /** 返回 annotations.basicObjective.entitiesHint 这部分的原始JSON文本，没有则返回null */
    public String entitiesHintJson() {
        JsonNode node = path("annotations", "basicObjective", "entitiesHint");
        return node != null && !node.isNull() && !(node.isArray() && node.isEmpty()) ? node.toString() : null;
    }

    public String summaryText() {
        return textAt("annotations", "basicObjective", "summary", "summaryText");
    }

    public String topicTypeLabel() {
        return textAt("annotations", "basicObjective", "topicType", "topicTypeLabel");
    }

    public String ideologyLabel() {
        return textAt("annotations", "highValueSubjective", "ideology", "ideologyLabel");
    }

    public java.util.List<String> languageStyleLabels() {
        return textArrayAt("annotations", "highValueSubjective", "languageStyle", "styleLabels");
    }

    public java.util.List<String> manipulationMethodLabels() {
        return textArrayAt("annotations", "highValueSubjective", "manipulationMethod", "methodLabels");
    }

    public String riskLabel() {
        return textAt("annotations", "highValueSubjective", "riskLevel", "riskLabel");
    }

    public java.util.List<String> riskTypes() {
        return textArrayAt("annotations", "highValueSubjective", "riskLevel", "riskTypes");
    }

    private java.util.List<String> textArrayAt(String... segments) {
        JsonNode arr = path(segments);
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<String> result = new java.util.ArrayList<>();
        arr.forEach(n -> {
            if (n != null && !n.isNull()) {
                result.add(n.asText(null));
            }
        });
        return result;
    }

    private String textAt(String... segments) {
        JsonNode node = path(segments);
        return node != null && !node.isNull() ? node.asText(null) : null;
    }

    private JsonNode path(String... segments) {
        if (root == null) {
            return null;
        }
        JsonNode node = root;
        for (String s : segments) {
            node = node.path(s);
        }
        return node.isMissingNode() ? null : node;
    }
}
