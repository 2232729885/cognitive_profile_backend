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

    public String topicSubcategory() {
        JsonNode arr = path("annotations", "basicObjective", "topicTags", "subtopicTags");
        return arr != null && arr.isArray() && !arr.isEmpty() ? arr.get(0).asText(null) : null;
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

    /** 返回 annotations.basicObjective.entitiesHint 这部分的原始JSON文本（供T2解析用），没有则返回null */
    public String entitiesHintJson() {
        JsonNode node = path("annotations", "basicObjective", "entitiesHint");
        return node != null && !node.isNull() && !(node.isArray() && node.isEmpty()) ? node.toString() : null;
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
