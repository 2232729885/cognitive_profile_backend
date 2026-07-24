package com.idata.profile.pipeline.step;

import com.idata.profile.agentproxy.AgentProxyClient;
import com.idata.profile.agentproxy.dto.t2.T2ExtractRequest;
import com.idata.profile.agentproxy.dto.t2.T2ExtractResponse;
import com.idata.profile.common.constant.AllowedRelationTypes;
import com.idata.profile.common.constant.PipelineStatus;
import com.idata.profile.entity.account.SocialAccount;
import com.idata.profile.entity.content.MediaContent;
import com.idata.profile.entity.raw.RawRecord;
import com.idata.profile.entity.task.PipelineTask;
import com.idata.profile.infra.neo4j.Neo4jGraphService;
import com.idata.profile.mapper.account.SocialAccountMapper;
import com.idata.profile.mapper.content.MediaContentMapper;
import com.idata.profile.mapper.raw.RawRecordMapper;
import com.idata.profile.mapper.task.PipelineTaskMapper;
import com.idata.profile.service.EntityResolutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class T2ExtractionStep {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");

    private final AgentProxyClient agentProxyClient;
    private final MediaContentMapper mediaContentMapper;
    private final RawRecordMapper rawRecordMapper;
    private final PipelineTaskMapper pipelineTaskMapper;
    private final Neo4jGraphService neo4jGraphService;
    private final SocialAccountMapper socialAccountMapper;
    private final EntityResolutionService entityResolutionService;

    @Value("${pipeline.t2.min-text-length:20}")
    private int minTextLength;

    @Value("${pipeline.t2.min-effective-token-count:3}")
    private int minEffectiveTokenCount;

    public void run(PipelineTask task) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        task.setT2Status("running");
        task.setT2StartedAt(startedAt);
        pipelineTaskMapper.updateById(task);

        MediaContent mc = mediaContentMapper.selectById(task.getContentId());
        if (mc == null) {
            throw new IllegalStateException("MediaContent not found, contentId=" + task.getContentId());
        }

        T2ExtractResponse response;
        SkipDecision skipDecision = evaluateSkip(mc);
        if (!skipDecision.skip()) {
            response = agentProxyClient.call(
                    "T2", "extract_entities", buildRequest(mc), T2ExtractResponse.class);
        } else {
            log.info("[T2] skip extraction: reason={}, contentId={}, effectiveTextLength={}, minTextLength={}, "
                            + "effectiveTokenCount={}, minEffectiveTokenCount={}",
                    skipDecision.reason(), mc.getId(), skipDecision.effectiveTextLength(), minTextLength,
                    skipDecision.effectiveTokenCount(), minEffectiveTokenCount);
            response = emptyResponse(mc, skipDecision);
        }
        if (response == null) {
            response = emptyResponse(mc, null);
        }

        EntityResolutionService.ResolutionResult resolutionResult = entityResolutionService.resolveMentions(
                response != null ? response.getEntities() : null,
                mc.getId().toString(),
                mc.getPlatform(),
                mc.getLanguage(),
                resolutionContextText(mc));
        Map<String, EntityResolutionService.ResolvedMention> resolvedMentions =
                resolutionResult.getResolvedMentions();

        if (mc.getAuthorAccountId() == null && hasText(response.getResolvedAuthorAccountId())) {
            mc.setAuthorAccountId(UUID.fromString(response.getResolvedAuthorAccountId()));
            mediaContentMapper.updateById(mc);
        }

        writeToNeo4j(task, response, mc, resolvedMentions);

        RawRecord rawRecord = rawRecordMapper.selectById(task.getRawRecordId());
        rawRecord.setT2Output(toJson(response));
        rawRecord.setT3Output(resolutionResult.getT3RawResponse());
        rawRecord.setPipelineStatus(PipelineStatus.T2_DONE.name());
        rawRecordMapper.updateById(rawRecord);

        task.setT2Status("done");
        task.setT2DoneAt(OffsetDateTime.now());
        task.setT2DurationMs((int) java.time.Duration.between(startedAt, OffsetDateTime.now()).toMillis());
        pipelineTaskMapper.updateById(task);
    }

    private T2ExtractRequest buildRequest(MediaContent mc) {
        T2ExtractRequest request = new T2ExtractRequest();
        request.setTitle(blankToNull(mc.getTitle()));
        request.setText(extractionText(mc));
        request.setLanguage(mc.getLanguage());
        Object t1Annotation = extractT1Annotation(mc);
        if (t1Annotation != null) {
            request.setT1Annotation(t1Annotation);
        }

        T2ExtractRequest.Context context = new T2ExtractRequest.Context();
        context.setContentId(mc.getId().toString());
        context.setPlatform(mc.getPlatform());
        context.setUrl(mc.getUrl());
        context.setPublishedAt(mc.getPublishedAt() != null ? mc.getPublishedAt().toString() : null);
        context.setAuthorHandle(mc.getAuthorPlatformUserId());
        context.setHashtags(mc.getHashtags());
        context.setMentions(mc.getMentions());
        context.setParentContentId(mc.getParentContentId());
        context.setRepostOfContentId(mc.getRepostOfContentId());
        context.setQuotedContentId(mc.getQuotedContentId());
        request.setContext(context);
        return request;
    }

    private SkipDecision evaluateSkip(MediaContent mc) {
        String text = extractionText(mc);
        if (!hasText(text)) {
            return new SkipDecision(true, "EMPTY_TEXT", 0, 0);
        }
        String effectiveText = normalizeEffectiveText(text);
        int effectiveTextLength = effectiveLength(effectiveText);
        int effectiveTokenCount = effectiveTokenCount(effectiveText);
        if (effectiveTextLength < Math.max(0, minTextLength)) {
            return new SkipDecision(true, "TEXT_TOO_SHORT", effectiveTextLength, effectiveTokenCount);
        }
        if (effectiveTokenCount < Math.max(0, minEffectiveTokenCount)) {
            return new SkipDecision(true, "TOO_FEW_EFFECTIVE_TOKENS", effectiveTextLength, effectiveTokenCount);
        }
        return new SkipDecision(false, null, effectiveTextLength, effectiveTokenCount);
    }

    private String resolutionContextText(MediaContent mc) {
        StringBuilder text = new StringBuilder();
        if (hasText(mc.getTitle())) {
            text.append(mc.getTitle().trim()).append('\n');
        }
        if (hasText(mc.getBodyText())) {
            text.append(mc.getBodyText().trim());
        }
        return text.toString().trim();
    }

    private String extractionText(MediaContent mc) {
        List<String> parts = new ArrayList<>();
        if (hasText(mc.getTitle())) {
            parts.add(mc.getTitle().trim());
        }
        if (hasText(mc.getBodyText())) {
            parts.add(mc.getBodyText().trim());
        }
        return String.join("\n\n", parts);
    }

    private T2ExtractResponse emptyResponse(MediaContent mc, SkipDecision skipDecision) {
        T2ExtractResponse response = new T2ExtractResponse();
        response.setContentId(mc.getId().toString());
        response.setEntities(List.of());
        response.setRelations(List.of());
        response.setResolvedAuthorAccountId(null);
        response.setModelVersion(skipDecision != null ? "backend-t2-skip-policy" : "backend-empty-response");
        response.setSkipped(skipDecision != null && skipDecision.skip());
        if (skipDecision != null) {
            response.setSkipReason(skipDecision.reason());
            response.setEffectiveTextLength(skipDecision.effectiveTextLength());
            response.setMinTextLength(Math.max(0, minTextLength));
            response.setEffectiveTokenCount(skipDecision.effectiveTokenCount());
            response.setMinEffectiveTokenCount(Math.max(0, minEffectiveTokenCount));
        }
        return response;
    }

    private String normalizeEffectiveText(String value) {
        if (!hasText(value)) {
            return "";
        }
        return URL_PATTERN.matcher(value)
                .replaceAll(" ")
                .replaceAll("[\\p{Cntrl}\\p{So}\\p{Sk}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private int effectiveLength(String value) {
        if (!hasText(value)) {
            return 0;
        }
        String compact = value.replaceAll("\\s+", "");
        return compact.codePointCount(0, compact.length());
    }

    private int effectiveTokenCount(String value) {
        if (!hasText(value)) {
            return 0;
        }
        int count = 0;
        boolean inLatinToken = false;
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            if (isCjkCodePoint(codePoint)) {
                count++;
                inLatinToken = false;
            } else if (Character.isLetterOrDigit(codePoint)) {
                if (!inLatinToken) {
                    count++;
                    inLatinToken = true;
                }
            } else {
                inLatinToken = false;
            }
            i += Character.charCount(codePoint);
        }
        return count;
    }

    private boolean isCjkCodePoint(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private Object extractT1Annotation(MediaContent mc) {
        String t1AnnotationJson = mc.getT1Annotation();
        if (!hasText(t1AnnotationJson)) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(t1AnnotationJson, Object.class);
        } catch (Exception e) {
            log.warn("Failed to parse T1 annotation for T2, contentId={}", mc.getId(), e);
            return null;
        }
    }

    private void writeToNeo4j(PipelineTask task, T2ExtractResponse response, MediaContent mc,
                              Map<String, EntityResolutionService.ResolvedMention> resolvedMentions) {
        try {
            writeRelations(response, resolvedMentions);
            writeMediaContentToNeo4j(task, mc);
            writeContentEntityRelations(mc, response, resolvedMentions);
        } catch (Exception e) {
            log.error("T2 Neo4j write failed, taskId={}", task.getId(), e);
        }
    }

    private void writeRelations(T2ExtractResponse response,
                                Map<String, EntityResolutionService.ResolvedMention> resolvedMentions) {
        if (response == null || response.getRelations() == null) {
            return;
        }
        for (T2ExtractResponse.ExtractedRelationMention rel : response.getRelations()) {
            if (rel == null || !hasText(rel.getSubjectMentionId()) || !hasText(rel.getObjectMentionId())
                    || !hasText(rel.getPredicate())) {
                continue;
            }
            if (!AllowedRelationTypes.VALUES.contains(rel.getPredicate())) {
                log.warn("Skip T2 relation with unsupported predicate={}, subjectMentionId={}, objectMentionId={}",
                        rel.getPredicate(), rel.getSubjectMentionId(), rel.getObjectMentionId());
                continue;
            }
            EntityResolutionService.ResolvedMention from = resolvedMentions.get(rel.getSubjectMentionId());
            EntityResolutionService.ResolvedMention to = resolvedMentions.get(rel.getObjectMentionId());
            if (from == null || to == null || !hasText(from.getLabel()) || !hasText(to.getLabel())) {
                continue;
            }
            try {
                Map<String, Object> props = new HashMap<>();
                props.put("source", "t2_realtime_resolution");
                props.put("extraction_method", "t2_relation_mention");
                props.put("relationMentionId", rel.getRelationMentionId());
                if (rel.getConfidence() != null) {
                    props.put("confidence", rel.getConfidence());
                }
                putIfHasText(props, "evidence", rel.getEvidence());
                boolean existed = neo4jGraphService.relationExists(from.getNodeId(), to.getNodeId(), rel.getPredicate());
                props.put("evidenceUpsert", existed);
                neo4jGraphService.mergeRelation(
                        from.getLabel(), from.getNodeId(),
                        to.getLabel(), to.getNodeId(),
                        rel.getPredicate(), props);
            } catch (Exception e) {
                log.warn("T2 relation write failed, predicate={}, subjectMentionId={}, objectMentionId={}",
                        rel.getPredicate(), rel.getSubjectMentionId(), rel.getObjectMentionId(), e);
            }
        }
    }

    private void writeContentEntityRelations(MediaContent mc,
                                             T2ExtractResponse response,
                                             Map<String, EntityResolutionService.ResolvedMention> resolvedMentions) {
        if (mc == null || resolvedMentions == null || resolvedMentions.isEmpty()) {
            return;
        }
        String contentNodeId = mc.getId().toString();
        Map<String, ContentEntityEvidence> evidences = collectContentEntityEvidences(mc, response, resolvedMentions);
        if (evidences.isEmpty()) {
            return;
        }
        for (ContentEntityEvidence evidence : evidences.values()) {
            try {
                Map<String, Object> props = evidence.toRelationProperties();
                boolean existed = neo4jGraphService.relationExists(contentNodeId, evidence.nodeId(), evidence.predicate());
                props.put("evidenceUpsert", existed);
                neo4jGraphService.mergeRelation(
                        "MediaContent", contentNodeId,
                        evidence.label(), evidence.nodeId(),
                        evidence.predicate(), props);
            } catch (Exception e) {
                log.warn("T2 content-entity relation write failed, contentId={}, entityId={}, predicate={}",
                        contentNodeId, evidence.nodeId(), evidence.predicate(), e);
            }
        }
    }

    private Map<String, ContentEntityEvidence> collectContentEntityEvidences(
            MediaContent mc,
            T2ExtractResponse response,
            Map<String, EntityResolutionService.ResolvedMention> resolvedMentions) {
        Map<String, ContentEntityEvidence> result = new HashMap<>();
        if (response == null || response.getEntities() == null || response.getEntities().isEmpty()) {
            return result;
        }
        String contextText = extractionText(mc);
        for (T2ExtractResponse.ExtractedMention mention : response.getEntities()) {
            if (mention == null || !hasText(mention.getMentionId())) {
                continue;
            }
            EntityResolutionService.ResolvedMention resolved = resolvedMentions.get(mention.getMentionId());
            if (resolved == null || !hasText(resolved.getNodeId()) || !hasText(resolved.getLabel())) {
                continue;
            }
            String predicate = "event".equals(resolved.getEntityType()) ? "DESCRIBES" : "MENTIONS";
            String key = predicate + ":" + resolved.getLabel() + ":" + resolved.getNodeId();
            ContentEntityEvidence evidence = result.computeIfAbsent(key,
                    ignored -> new ContentEntityEvidence(predicate, resolved.getLabel(), resolved.getNodeId()));
            evidence.add(mc, mention, resolved, contextText);
        }
        return result;
    }

    private void writeMediaContentToNeo4j(PipelineTask task, MediaContent content) {
        try {
            if (content == null) {
                log.debug("Skip structural MediaContent Neo4j write because content was not found, taskId={}",
                        task.getId());
                return;
            }

            mergeMediaContentNode(content);

            if (content.getAuthorAccountId() != null) {
                mergeSocialAccountNode(content.getAuthorAccountId());
                neo4jGraphService.mergeRelation(
                        "MediaContent", content.getId().toString(),
                        "SocialAccount", content.getAuthorAccountId().toString(),
                        "PUBLISHED_BY",
                        Map.of("source", "backend_structural",
                                "extraction_method", "author_field_lookup"));
            }

            boolean allSynced = true;
            allSynced &= writePropagationRelation(content, content.getParentContentId(), "REPLY_TO");
            allSynced &= writePropagationRelation(content, content.getRepostOfContentId(), "REPOSTS");
            if (!sameContentId(content.getRootContentId(), content.getPlatformContentId())) {
                allSynced &= writePropagationRelation(content, content.getRootContentId(), "REPLY_TO");
            }
            if (allSynced) {
                mediaContentMapper.markPropagationSyncedToNeo4j(content.getId());
            }

        } catch (Exception e) {
            log.warn("Structural MediaContent Neo4j write failed, taskId={}, contentId={}",
                    task.getId(), task.getContentId(), e);
        }
    }

    private boolean writePropagationRelation(MediaContent content, String targetPlatformContentId, String relationType) {
        if (!hasText(targetPlatformContentId)) {
            return true;
        }
        if (!hasText(content.getPlatform())) {
            return false;
        }

        try {
            MediaContent target = mediaContentMapper.selectByPlatformAndContentId(
                    content.getPlatform(), targetPlatformContentId.trim());
            if (target == null) {
                return false;
            }
            mergeMediaContentNode(target);
            neo4jGraphService.mergeRelation(
                    "MediaContent", content.getId().toString(),
                    "MediaContent", target.getId().toString(),
                    relationType,
                    Map.of("source", "backend_structural",
                            "extraction_method", "propagation_chain_field"));
            return true;
        } catch (Exception e) {
            log.warn("Failed to write propagation relation to Neo4j, contentId={}, relationType={}, "
                            + "targetPlatformContentId={}",
                    content.getId(), relationType, targetPlatformContentId, e);
            return false;
        }
    }

    private void mergeMediaContentNode(MediaContent content) {
        Map<String, Object> contentProps = new HashMap<>();
        putIfHasText(contentProps, "title", content.getTitle());
        putIfHasText(contentProps, "bodyText", content.getBodyText());
        putIfHasText(contentProps, "translatedTitle", content.getTranslatedTitle());
        putIfHasText(contentProps, "translatedBodyText", content.getTranslatedBodyText());
        putIfHasText(contentProps, "translatedSummary", content.getTranslatedSummary());
        putIfHasText(contentProps, "platform", content.getPlatform());
        putIfHasText(contentProps, "contentType", content.getContentType());
        putIfHasText(contentProps, "language", content.getLanguage());
        putIfHasText(contentProps, "platformContentId", content.getPlatformContentId());
        putIfNotNull(contentProps, "publishedAt",
                content.getPublishedAt() != null ? content.getPublishedAt().toString() : null);
        putIfHasText(contentProps, "url", content.getUrl());
        putIfHasText(contentProps, "t1Annotation", content.getT1Annotation());
        putIfNotNull(contentProps, "likeCount", content.getLikeCount());
        putIfNotNull(contentProps, "commentCount", content.getCommentCount());
        putIfNotNull(contentProps, "shareCount", content.getShareCount());
        putIfNotNull(contentProps, "repostCount", content.getRepostCount());
        putIfNotNull(contentProps, "quoteCount", content.getQuoteCount());
        putIfNotNull(contentProps, "viewCount", content.getViewCount());
        putIfNotNull(contentProps, "reactionCount", content.getReactionCount());
        if (content.getMediaAssetIds() != null && content.getMediaAssetIds().length > 0) {
            String[] mediaAssetIdStrings = new String[content.getMediaAssetIds().length];
            for (int i = 0; i < content.getMediaAssetIds().length; i++) {
                mediaAssetIdStrings[i] = content.getMediaAssetIds()[i].toString();
            }
            contentProps.put("mediaAssetIds", mediaAssetIdStrings);
        }
        contentProps.put("source", "backend_structural");
        neo4jGraphService.mergeNode("MediaContent", content.getId().toString(), contentProps);
    }

    private void mergeSocialAccountNode(UUID accountId) {
        SocialAccount account = socialAccountMapper.selectById(accountId);
        Map<String, Object> accountProps = new HashMap<>();
        if (account != null) {
            putIfHasText(accountProps, "platform", account.getPlatform());
            putIfHasText(accountProps, "platformUserId", account.getPlatformUserId());
            putIfHasText(accountProps, "handle", account.getHandle());
            putIfHasText(accountProps, "displayName", account.getDisplayName());
        }
        accountProps.put("source", "backend_structural");
        neo4jGraphService.mergeNode("SocialAccount", accountId.toString(), accountProps);
    }

    private boolean sameContentId(String left, String right) {
        return hasText(left) && hasText(right) && left.trim().equals(right.trim());
    }

    private void putIfHasText(Map<String, Object> target, String key, String value) {
        if (hasText(value)) {
            target.put(key, value);
        }
    }

    private String blankToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String toJson(T2ExtractResponse response) {
        if (response == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(response);
        } catch (Exception e) {
            log.warn("Failed to serialize T2 response");
            return null;
        }
    }

    private static class ContentEntityEvidence {
        private final String predicate;
        private final String label;
        private final String nodeId;
        private final List<String> mentionIds = new ArrayList<>();
        private final List<String> mentionNames = new ArrayList<>();
        private final List<String> canonicalNames = new ArrayList<>();
        private final List<String> evidenceTexts = new ArrayList<>();
        private final List<String> evidenceWindows = new ArrayList<>();
        private Integer firstSpanStart;
        private Integer firstSpanEnd;
        private Double maxConfidence;
        private Double maxImportanceScore;
        private String contentTitle;
        private String contentUrl;
        private String platformContentId;

        ContentEntityEvidence(String predicate, String label, String nodeId) {
            this.predicate = predicate;
            this.label = label;
            this.nodeId = nodeId;
        }

        void add(MediaContent content,
                 T2ExtractResponse.ExtractedMention mention,
                 EntityResolutionService.ResolvedMention resolved,
                 String contextText) {
            addDistinct(mentionIds, mention.getMentionId());
            addDistinct(mentionNames, firstText(mention.getName(), resolved.getName()));
            addDistinct(canonicalNames, firstText(mention.getCanonicalName(), resolved.getName()));
            EvidenceSpan evidenceSpan = evidenceSpan(contextText, mention);
            addDistinct(evidenceTexts, evidenceSpan.text());
            addDistinct(evidenceWindows, evidenceSpan.window());
            if (firstSpanStart == null && evidenceSpan.start() != null) {
                firstSpanStart = evidenceSpan.start();
            }
            if (firstSpanEnd == null && evidenceSpan.end() != null) {
                firstSpanEnd = evidenceSpan.end();
            }
            maxConfidence = max(maxConfidence, mention.getConfidence());
            maxImportanceScore = max(maxImportanceScore, mention.getImportanceScore());
            contentTitle = firstText(contentTitle, content.getTitle());
            contentUrl = firstText(contentUrl, content.getUrl());
            platformContentId = firstText(platformContentId, content.getPlatformContentId());
        }

        Map<String, Object> toRelationProperties() {
            Map<String, Object> props = new HashMap<>();
            props.put("source", "t2_content_entity_link");
            props.put("extraction_method", "t2_entity_mention");
            props.put("mentionIds", mentionIds.toArray(String[]::new));
            props.put("mentionNames", mentionNames.toArray(String[]::new));
            props.put("canonicalNames", canonicalNames.toArray(String[]::new));
            props.put("evidenceTexts", evidenceTexts.toArray(String[]::new));
            props.put("evidenceWindows", evidenceWindows.toArray(String[]::new));
            putIfNotNull(props, "spanStart", firstSpanStart);
            putIfNotNull(props, "spanEnd", firstSpanEnd);
            putIfNotNull(props, "confidence", maxConfidence);
            putIfNotNull(props, "importanceScore", maxImportanceScore);
            putIfHasText(props, "contentTitle", contentTitle);
            putIfHasText(props, "contentUrl", contentUrl);
            putIfHasText(props, "platformContentId", platformContentId);
            return props;
        }

        String predicate() {
            return predicate;
        }

        String label() {
            return label;
        }

        String nodeId() {
            return nodeId;
        }

        private static EvidenceSpan evidenceSpan(String contextText, T2ExtractResponse.ExtractedMention mention) {
            if (!hasText(contextText)) {
                return new EvidenceSpan(firstText(mention.getCanonicalName(), mention.getName()), null, null, null);
            }
            T2ExtractResponse.ExtractedMention.Span span = mention.getSpan();
            if (span != null && span.getStart() != null && span.getEnd() != null
                    && span.getStart() >= 0 && span.getEnd() > span.getStart()
                    && span.getEnd() <= contextText.length()) {
                int windowStart = Math.max(0, span.getStart() - 120);
                int windowEnd = Math.min(contextText.length(), span.getEnd() + 120);
                return new EvidenceSpan(
                        contextText.substring(span.getStart(), span.getEnd()),
                        contextText.substring(windowStart, windowEnd),
                        span.getStart(),
                        span.getEnd());
            }
            String name = firstText(mention.getName(), mention.getCanonicalName());
            if (!hasText(name)) {
                return new EvidenceSpan(null, abbreviate(contextText, 240), null, null);
            }
            int index = contextText.indexOf(name);
            if (index >= 0) {
                int windowStart = Math.max(0, index - 120);
                int windowEnd = Math.min(contextText.length(), index + name.length() + 120);
                return new EvidenceSpan(name, contextText.substring(windowStart, windowEnd),
                        index, index + name.length());
            }
            return new EvidenceSpan(name, abbreviate(contextText, 240), null, null);
        }

        private static Double max(Double current, Double candidate) {
            if (candidate == null) {
                return current;
            }
            return current == null ? candidate : Math.max(current, candidate);
        }

        private static void addDistinct(List<String> values, String value) {
            if (hasText(value) && !values.contains(value.trim())) {
                values.add(value.trim());
            }
        }

        private static String abbreviate(String value, int maxLength) {
            if (value == null || value.length() <= maxLength) {
                return value;
            }
            return value.substring(0, maxLength);
        }

        private static String firstText(String... values) {
            if (values == null) {
                return null;
            }
            for (String value : values) {
                if (hasText(value)) {
                    return value.trim();
                }
            }
            return null;
        }

        private static void putIfHasText(Map<String, Object> target, String key, String value) {
            if (hasText(value)) {
                target.put(key, value);
            }
        }

        private static void putIfNotNull(Map<String, Object> target, String key, Object value) {
            if (value != null) {
                target.put(key, value);
            }
        }

        private static boolean hasText(String value) {
            return value != null && !value.trim().isEmpty();
        }
    }

    private record EvidenceSpan(String text, String window, Integer start, Integer end) {
    }

    private record SkipDecision(
            boolean skip,
            String reason,
            int effectiveTextLength,
            int effectiveTokenCount) {
    }
}
