package com.idata.profile.ingestion.normalizer;

import com.idata.profile.common.constant.RecordType;
import com.idata.profile.common.util.HashUtil;
import com.idata.profile.entity.content.MediaContent;
import com.idata.profile.entity.raw.RawRecord;
import com.idata.profile.ingestion.consumer.IngestionMessageSupport;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class SocialContentNormalizer {

    public MediaContent normalize(Object kafkaMessage, RawRecord rawRecord) {
        JsonNode root = IngestionMessageSupport.root(kafkaMessage);
        JsonNode data = IngestionMessageSupport.data(kafkaMessage);
        JsonNode rawPayload = root.path("raw_payload");
        JsonNode metrics = data.path("metrics");

        MediaContent mc = new MediaContent();
        mc.setId(UUID.randomUUID());
        mc.setRawRecordId(rawRecord.getId());
        mc.setPlatform(rawRecord.getPlatform());
        mc.setContentType(rawRecord.getContentType());
        mc.setPlatformContentId(IngestionMessageSupport.text(data, "platform_content_id"));
        mc.setAuthorPlatformUserId(IngestionMessageSupport.text(data, "author_platform_user_id"));
        mc.setTitle(IngestionMessageSupport.firstText(data, "title", "headline"));
        if (!IngestionMessageSupport.hasText(mc.getTitle())) {
            mc.setTitle(IngestionMessageSupport.text(rawPayload, "title"));
        }
        mc.setBodyText(IngestionMessageSupport.firstText(data, "body_text", "text", "content"));
        mc.setLanguage(IngestionMessageSupport.firstText(data, "language", "lang"));
        if (!IngestionMessageSupport.hasText(mc.getLanguage())) {
            mc.setLanguage(IngestionMessageSupport.text(root, "language"));
        }
        mc.setPublishedAt(IngestionMessageSupport.parseOffsetDateTime(data.path("published_at")));
        mc.setUrl(IngestionMessageSupport.firstText(data, "url", "source_url"));
        if (!IngestionMessageSupport.hasText(mc.getUrl())) {
            mc.setUrl(rawRecord.getSourceUrl());
        }
        mc.setParentContentId(IngestionMessageSupport.text(data, "parent_content_id"));
        mc.setRootContentId(IngestionMessageSupport.text(data, "root_content_id"));
        mc.setRepostOfContentId(IngestionMessageSupport.text(data, "repost_of_content_id"));
        mc.setQuotedContentId(IngestionMessageSupport.text(data, "quoted_content_id"));
        mc.setHashtags(readStringArray(data.path("hashtags")));
        mc.setMentions(readStringArray(data.path("mentions")));
        mc.setExternalUrls(readStringArray(data.path("external_urls")));
        mc.setSourceMediaAssetIds(readStringArray(data.path("media_asset_ids")));
        mc.setLikeCount(readLong(data, metrics, "like_count"));
        mc.setCommentCount(readLong(data, metrics, "comment_count"));
        mc.setShareCount(readLong(data, metrics, "share_count"));
        mc.setRepostCount(readLong(data, metrics, "repost_count"));
        mc.setQuoteCount(readLong(data, metrics, "quote_count"));
        mc.setViewCount(readLong(data, metrics, "view_count"));
        mc.setReactionCount(readLong(data, metrics, "reaction_count"));

        if (RecordType.NEWS_ARTICLE.getCode().equals(rawRecord.getRecordType())) {
            mc.setContentType("article");
            mc.setPlatform("news");
            if (!IngestionMessageSupport.hasText(mc.getPlatformContentId())
                    && IngestionMessageSupport.hasText(rawRecord.getSourceUrl())) {
                mc.setPlatformContentId(HashUtil.sha256(rawRecord.getSourceUrl()));
            }
            mc.setNewsSourceName(IngestionMessageSupport.text(data, "source_name"));
            mc.setNewsDomain(IngestionMessageSupport.text(data, "domain"));
            mc.setNewsAuthor(IngestionMessageSupport.text(data, "author"));
            mc.setNewsSection(IngestionMessageSupport.text(data, "section"));
            mc.setNewsTags(readStringArray(data.path("tags")));
        }

        return mc;
    }

    private Long readLong(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asLong();
        }
        String text = value.asText();
        if (!IngestionMessageSupport.hasText(text)) {
            return null;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long readLong(JsonNode primaryNode, JsonNode fallbackNode, String fieldName) {
        Long value = readLong(primaryNode, fieldName);
        return value != null ? value : readLong(fallbackNode, fieldName);
    }

    private String[] readStringArray(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }

        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                String value = item.asText();
                if (IngestionMessageSupport.hasText(value)) {
                    values.add(value.trim());
                }
            }
        } else {
            String value = node.asText();
            if (IngestionMessageSupport.hasText(value)) {
                values.add(value.trim());
            }
        }
        return values.isEmpty() ? null : values.toArray(String[]::new);
    }
}
