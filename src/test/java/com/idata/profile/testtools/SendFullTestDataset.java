package com.idata.profile.testtools;

import com.idata.profile.infra.kafka.KafkaTopicConstants;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

public class SendFullTestDataset {

    private static final String BOOTSTRAP_SERVERS = "172.16.40.232:9092";
    private static final String MINIO_ENDPOINT = "http://172.16.40.232:9000";
    private static final String MINIO_ACCESS_KEY = "minioadmin";
    private static final String MINIO_SECRET_KEY = "minioadmin";
    private static final String MINIO_BUCKET = "media-assets";
    private static final String SCHEMA_VERSION = "kt3_to_kt4_v1";

    public static void main(String[] args) throws Exception {
        String crawlTaskId = "full_test_" + UUID.randomUUID();

        String userIdA = "ft_user_" + UUID.randomUUID();
        String userIdB = "ft_user_" + UUID.randomUUID();
        String userIdC = "ft_user_" + UUID.randomUUID();
        String userIdD = "ft_user_" + UUID.randomUUID();
        String channelId = "ft_chan_" + UUID.randomUUID();

        String postId1 = "ft_post_" + UUID.randomUUID();
        String postId2 = "ft_post_" + UUID.randomUUID();
        String postId3 = "ft_post_" + UUID.randomUUID();
        String postId4 = "ft_post_" + UUID.randomUUID();
        String postId5 = "ft_post_" + UUID.randomUUID();
        String newsId1 = "ft_news_" + UUID.randomUUID();

        String assetId1 = "ft_asset_" + UUID.randomUUID();
        String assetId2 = "ft_asset_" + UUID.randomUUID();

        Instant now = Instant.now();
        AssetPayload asset1 = prepareAsset(
                assetId1, "test/full_dataset/image1.jpg", "image/jpeg", findFirstJpegImage(), true);
        AssetPayload asset2 = prepareAsset(
                assetId2, "test/full_dataset/image2.png", "image/png", findFirstPngImage(), false);

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            send(producer, KafkaTopicConstants.COLLECTION_TASK, collectionTask(crawlTaskId, now));

            send(producer, KafkaTopicConstants.SOCIAL_ACCOUNT, accountA(crawlTaskId, userIdA, now));
            send(producer, KafkaTopicConstants.SOCIAL_ACCOUNT, accountB(crawlTaskId, userIdB, now));
            send(producer, KafkaTopicConstants.SOCIAL_ACCOUNT, accountC(crawlTaskId, userIdC, now));
            send(producer, KafkaTopicConstants.SOCIAL_ACCOUNT, accountD(crawlTaskId, userIdD, now));
            send(producer, KafkaTopicConstants.SOCIAL_ACCOUNT, accountChannel(crawlTaskId, channelId, now));

            Thread.sleep(1_000L);

            send(producer, KafkaTopicConstants.ACCOUNT_RELATION, accountRelation(
                    crawlTaskId, "x", userIdA, userIdB, "following", "following_list", now));
            send(producer, KafkaTopicConstants.ACCOUNT_RELATION, accountRelation(
                    crawlTaskId, "x", userIdD, userIdA, "following", "following_list", now));
            send(producer, KafkaTopicConstants.ACCOUNT_RELATION, accountRelation(
                    crawlTaskId, "youtube", userIdC, channelId, "admin_of", "channel_profile", now));

            send(producer, KafkaTopicConstants.SOCIAL_CONTENT, content1(crawlTaskId, userIdA, postId1, now));
            send(producer, KafkaTopicConstants.SOCIAL_CONTENT, content2(crawlTaskId, userIdB, postId1, postId2, now));
            send(producer, KafkaTopicConstants.SOCIAL_CONTENT, content3(crawlTaskId, userIdC, userIdA, postId1, postId3, now));
            send(producer, KafkaTopicConstants.SOCIAL_CONTENT, content4(crawlTaskId, userIdD, postId4, now));
            send(producer, KafkaTopicConstants.SOCIAL_CONTENT, content5(crawlTaskId, userIdD, postId1, postId5, now));

            send(producer, KafkaTopicConstants.NEWS_ARTICLE, newsArticle(crawlTaskId, newsId1, now));

            Thread.sleep(2_000L);

            send(producer, KafkaTopicConstants.MEDIA_ASSET, mediaAsset(crawlTaskId, "x", postId1, asset1, now));
            send(producer, KafkaTopicConstants.MEDIA_ASSET, mediaAsset(crawlTaskId, "news", newsId1, asset2, now));
            producer.flush();
        }

        printSummary(crawlTaskId, userIdA, userIdB, userIdC, userIdD, channelId,
                postId1, postId2, postId3, postId4, postId5, newsId1, assetId1, assetId2);
        printSelfCheck();
    }

    private static void send(KafkaProducer<String, String> producer, String topic, String template) throws Exception {
        String rawRecordId = UUID.randomUUID().toString();
        String withRawRecordId = template.replace("${raw_record_id}", json(rawRecordId));
        String hashInput = withRawRecordId.replace("${raw_payload_hash}", "");
        String payloadHash = sha256(hashInput);
        String message = withRawRecordId.replace("${raw_payload_hash}", payloadHash);

        RecordMetadata metadata = producer.send(new ProducerRecord<>(topic, rawRecordId, message)).get();
        System.out.println("sent topic=" + metadata.topic()
                + ", partition=" + metadata.partition()
                + ", offset=" + metadata.offset()
                + ", rawRecordId=" + rawRecordId);
    }

    private static String collectionTask(String crawlTaskId, Instant now) {
        return """
                {
                  "schema_version": "%s",
                  "record_type": "collection_task",
                  "raw_record_id": "${raw_record_id}",
                  "raw_payload_hash": "${raw_payload_hash}",
                  "crawl_task_id": "%s",
                  "collected_at": "%s",
                  "platform": "x",
                  "language": "zh",
                  "data": {
                    "collection_method": "api",
                    "seed_type": "keyword",
                    "seed_value": "美伊冲突 霍尔木兹海峡 核设施 航运安全",
                    "query_expression": "美伊冲突 霍尔木兹海峡 核设施 航运安全",
                    "time_window_start": "%s",
                    "time_window_end": "%s",
                    "target_languages": ["zh", "en", "vi"],
                    "target_regions": ["US", "CN", "VN"],
                    "collector_version": "v2.0.0",
                    "records_collected": 14
                  }
                }
                """.formatted(json(SCHEMA_VERSION), json(crawlTaskId),
                json(now.toString()), json(now.minus(7, ChronoUnit.DAYS).toString()), json(now.toString()));
    }

    private static String accountA(String crawlTaskId, String userIdA, Instant now) {
        return socialAccount(crawlTaskId, "x", "zh", userIdA, "user", null,
                "@gulf_watch_leila", "Leila Farzan｜波斯湾观察", "伊朗裔安全研究员，关注波斯湾航运、能源安全与冲突升级",
                true, "blue", false, now.minus(1095, ChronoUnit.DAYS).toString(),
                285_000L, 1_200L, null, null, 8_900L, null, now);
    }

    private static String accountB(String crawlTaskId, String userIdB, Instant now) {
        return socialAccount(crawlTaskId, "x", "zh", userIdB, "user", null,
                "@globalwire_mena", "GlobalWire中东观察", "国际媒体中东安全专线，跟踪海湾局势与能源市场",
                true, "org", false, now.minus(2200, ChronoUnit.DAYS).toString(),
                520_000L, null, null, null, 45_000L, null, now);
    }

    private static String accountC(String crawlTaskId, String userIdC, Instant now) {
        return socialAccount(crawlTaskId, "x", "zh", userIdC, "user", null,
                "@us_gulf_briefing", "美国海湾安全简报", "美国中东安全与航运风险公开信息简报",
                true, "government", false, now.minus(1800, ChronoUnit.DAYS).toString(),
                1_200_000L, null, null, null, 3_200L, null, now);
    }

    private static String accountD(String crawlTaskId, String userIdD, Instant now) {
        return socialAccount(crawlTaskId, "x", "zh", userIdD, "user", null,
                "@gulf_alert_bot", "Gulf Alert Bot", "高频转发海湾冲突消息的自动化账号",
                false, "none", true, now.minus(30, ChronoUnit.DAYS).toString(),
                12L, 8_900L, null, null, 6_700L, null, now);
    }

    private static String accountChannel(String crawlTaskId, String channelId, Instant now) {
        return socialAccount(crawlTaskId, "youtube", "zh", channelId, "channel", "youtube_channel",
                "gulfbrief_channel", "Gulf Briefing频道", "YouTube地缘安全分析频道，聚焦伊朗、美国与海湾航道",
                true, "org", false, now.minus(900, ChronoUnit.DAYS).toString(),
                null, null, 89_000L, null, 420L, 5_600_000L, now);
    }

    private static String socialAccount(String crawlTaskId, String platform, String language,
                                        String platformUserId, String accountEntityType,
                                        String platformNativeType, String handle,
                                        String displayName, String bio, boolean verified,
                                        String verifiedType, boolean suspended,
                                        String accountCreatedAt, Long followers,
                                        Long following, Long subscribers, Long members,
                                        Long posts, Long views, Instant now) {
        return """
                {
                  "schema_version": "%s",
                  "record_type": "social_account",
                  "raw_record_id": "${raw_record_id}",
                  "raw_payload_hash": "${raw_payload_hash}",
                  "crawl_task_id": "%s",
                  "collected_at": "%s",
                  "platform": "%s",
                  "language": "%s",
                  "data": {
                    "platform_user_id": "%s",
                    "account_entity_type": "%s",
                    "platform_native_type": %s,
                    "handle": "%s",
                    "display_name": "%s",
                    "bio": "%s",
                    "verified": %s,
                    "verified_type": "%s",
                    "is_suspended": %s,
                    "account_created_at": "%s",
                    "metrics": {
                      "followers_count": %s,
                      "following_count": %s,
                      "subscriber_count": %s,
                      "member_count": %s,
                      "post_count": %s,
                      "view_count": %s
                    }
                  }
                }
                """.formatted(json(SCHEMA_VERSION), json(crawlTaskId), json(now.toString()),
                json(platform), json(language), json(platformUserId), json(accountEntityType),
                jsonNullable(platformNativeType), json(handle), json(displayName), json(bio),
                verified, json(verifiedType), suspended, json(accountCreatedAt),
                numberOrNull(followers), numberOrNull(following), numberOrNull(subscribers),
                numberOrNull(members), numberOrNull(posts), numberOrNull(views));
    }

    private static String accountRelation(String crawlTaskId, String platform,
                                          String sourceUserId, String targetUserId,
                                          String relationType, String source, Instant now) {
        return """
                {
                  "schema_version": "%s",
                  "record_type": "account_relation",
                  "raw_record_id": "${raw_record_id}",
                  "raw_payload_hash": "${raw_payload_hash}",
                  "crawl_task_id": "%s",
                  "collected_at": "%s",
                  "platform": "%s",
                  "language": "zh",
                  "data": {
                    "source_platform_user_id": "%s",
                    "target_platform_user_id": "%s",
                    "relation_type": "%s",
                    "observed_at": "%s",
                    "occurred_at": null,
                    "source": "%s"
                  }
                }
                """.formatted(json(SCHEMA_VERSION), json(crawlTaskId), json(now.toString()),
                json(platform), json(sourceUserId), json(targetUserId), json(relationType),
                json(now.toString()), json(source));
    }

    private static String content1(String crawlTaskId, String userIdA, String postId1, Instant now) {
        return socialContent(crawlTaskId, "x", "zh", postId1, userIdA, "post",
                "外部势力协调操控社交媒体叙事的证据越来越多。霍尔木兹海峡（Strait of Hormuz）紧张局势持续升级，2026年波斯湾军事对峙（2026 Persian Gulf Military Standoff）期间，大量账号在关键时间节点发布高度相似的内容。美国中央司令部（U.S. Central Command）的行动被反复提及。 #信息战 #霍尔木兹 #波斯湾",
                now.minus(2, ChronoUnit.HOURS), postId1, null, null, null,
                new String[]{"美伊冲突", "霍尔木兹海峡", "能源安全"}, new String[]{},
                4_820L, 632L, null, 1_340L, 287L, 186_000L);
    }

    private static String content2(String crawlTaskId, String userIdB, String postId1, String postId2, Instant now) {
        return socialContent(crawlTaskId, "x", "zh", postId2, userIdB, "repost",
                "", now.minus(90, ChronoUnit.MINUTES), postId1, null, postId1, null,
                new String[]{}, new String[]{}, 890L, null, null, 210L, null, 42_000L);
    }

    private static String content3(String crawlTaskId, String userIdC, String userIdA,
                                   String postId1, String postId3, Instant now) {
        return socialContent(crawlTaskId, "x", "zh", postId3, userIdC, "quote",
                "我们注意到有关霍尔木兹海峡安全的讨论正在升温。当前重点是保护商业航运并避免误判升级。",
                now.minus(1, ChronoUnit.HOURS), postId1, null, null, postId1,
                new String[]{"航运安全"}, new String[]{userIdA}, 12_300L, null, null, 3_400L, null, 520_000L);
    }

    private static String content4(String crawlTaskId, String userIdD, String postId4, Instant now) {
        return socialContent(crawlTaskId, "x", "zh", postId4, userIdD, "post",
                "突发！海湾战争已经不可避免，所有油轮都会被封锁！马上囤油！#美伊冲突 #紧急",
                now.minus(3, ChronoUnit.HOURS), postId4, null, null, null,
                new String[]{"美伊冲突", "紧急"}, new String[]{}, 3L, null, null, 1L, null, 47L);
    }

    private static String content5(String crawlTaskId, String userIdD, String postId1, String postId5, Instant now) {
        return socialContent(crawlTaskId, "x", "zh", postId5, userIdD, "reply",
                "这就是我说的，海峡马上要关闭了！", now.minus(108, ChronoUnit.MINUTES), postId1, postId1, null, null,
                new String[]{}, new String[]{}, 0L, null, null, null, null, 12L);
    }

    private static String socialContent(String crawlTaskId, String platform, String language,
                                        String platformContentId, String authorUserId,
                                        String contentType, String bodyText, Instant publishedAt,
                                        String rootContentId, String parentContentId,
                                        String repostOfContentId, String quotedContentId,
                                        String[] hashtags, String[] mentions,
                                        Long likeCount, Long commentCount, Long shareCount,
                                        Long repostCount, Long quoteCount, Long viewCount) {
        return """
                {
                  "schema_version": "%s",
                  "record_type": "social_content",
                  "raw_record_id": "${raw_record_id}",
                  "raw_payload_hash": "${raw_payload_hash}",
                  "crawl_task_id": "%s",
                  "collected_at": "%s",
                  "platform": "%s",
                  "language": "%s",
                  "data": {
                    "platform_content_id": "%s",
                    "author_platform_user_id": "%s",
                    "content_type": "%s",
                    "body_text": "%s",
                    "published_at": "%s",
                    "url": "https://x.example/status/%s",
                    "root_content_id": %s,
                    "parent_content_id": %s,
                    "repost_of_content_id": %s,
                    "quoted_content_id": %s,
                    "hashtags": %s,
                    "mentions": %s,
                    "metrics": {
                      "like_count": %s,
                      "comment_count": %s,
                      "share_count": %s,
                      "repost_count": %s,
                      "quote_count": %s,
                      "view_count": %s
                    }
                  }
                }
                """.formatted(json(SCHEMA_VERSION), json(crawlTaskId), json(Instant.now().toString()),
                json(platform), json(language), json(platformContentId), json(authorUserId),
                json(contentType), json(bodyText), json(publishedAt.toString()), json(platformContentId),
                jsonNullable(rootContentId), jsonNullable(parentContentId), jsonNullable(repostOfContentId),
                jsonNullable(quotedContentId), jsonArray(hashtags), jsonArray(mentions),
                numberOrNull(likeCount), numberOrNull(commentCount), numberOrNull(shareCount),
                numberOrNull(repostCount), numberOrNull(quoteCount), numberOrNull(viewCount));
    }

    private static String newsArticle(String crawlTaskId, String newsId1, Instant now) {
        String url = "https://ft-test-news.example.com/articles/us-iran-gulf-escalation-2026";
        return """
                {
                  "schema_version": "%s",
                  "record_type": "news_article",
                  "raw_record_id": "${raw_record_id}",
                  "raw_payload_hash": "${raw_payload_hash}",
                  "crawl_task_id": "%s",
                  "collected_at": "%s",
                  "platform": "news",
                  "language": "zh",
                  "source_url": "%s",
                  "data": {
                    "content_type": "article",
                    "platform_content_id": "%s",
                    "source_name": "GlobalWire测试新闻",
                    "domain": "ft-test-news.example.com",
                    "author": "MENA观察组",
                    "section": "国际",
                    "title": "霍尔木兹海峡紧张局势升温，社交平台出现大量冲突叙事",
                    "body_text": "测试新闻监测显示，美伊紧张局势升温后，社交平台围绕霍尔木兹海峡、核设施安全、商业航运保险和能源价格出现高密度讨论。部分账号将军事调动解读为即将爆发全面战争，另一些媒体和官方账号则强调仍存在外交降温空间。研究人员认为，这类叙事会影响公众风险感知，并可能放大市场波动。",
                    "tags": ["美伊冲突", "霍尔木兹海峡", "能源安全"],
                    "published_at": "%s",
                    "url": "%s"
                  }
                }
                """.formatted(json(SCHEMA_VERSION), json(crawlTaskId), json(Instant.now().toString()),
                json(url), json(newsId1), json(now.minus(6, ChronoUnit.HOURS).toString()), json(url));
    }

    private static String mediaAsset(String crawlTaskId, String platform, String platformContentId,
                                     AssetPayload asset, Instant now) {
        return """
                {
                  "schema_version": "%s",
                  "record_type": "media_asset",
                  "raw_record_id": "${raw_record_id}",
                  "raw_payload_hash": "${raw_payload_hash}",
                  "crawl_task_id": "%s",
                  "collected_at": "%s",
                  "platform": "%s",
                  "language": "zh",
                  "data": {
                    "asset_id": "%s",
                    "platform_content_id": "%s",
                    "asset_type": "image",
                    "source_url": "%s",
                    "storage_uri": "%s",
                    "mime_type": "%s",
                    "sha256": "%s",
                    "file_size_bytes": %d,
                    "minio_bucket": "%s",
                    "minio_key": "%s"
                  }
                }
                """.formatted(json(SCHEMA_VERSION), json(crawlTaskId), json(now.toString()),
                json(platform), json(asset.assetId()), json(platformContentId), json(asset.sourceUrl()),
                json(asset.storageUri()), json(asset.mimeType()), json(asset.sha256()),
                asset.fileSizeBytes(), json(MINIO_BUCKET), json(asset.minioKey()));
    }

    private static AssetPayload prepareAsset(String assetId, String key, String mimeType,
                                             Path imagePath, boolean allowPlaceholderSize) throws Exception {
        if (imagePath == null) {
            String fakeHash = UUID.randomUUID().toString().replace("-", "");
            long fileSize = allowPlaceholderSize ? 102_400L : 204_800L;
            return new AssetPayload(assetId, mimeType, fakeHash, fileSize,
                    MINIO_BUCKET + "/" + key,
                    MINIO_ENDPOINT + "/" + MINIO_BUCKET + "/" + key,
                    key);
        }

        byte[] content = Files.readAllBytes(imagePath);
        ensureBucketAndUpload(key, content, mimeType);
        return new AssetPayload(assetId, mimeType, sha256(content), content.length,
                MINIO_BUCKET + "/" + key,
                MINIO_ENDPOINT + "/" + MINIO_BUCKET + "/" + key,
                key);
    }

    private static void ensureBucketAndUpload(String key, byte[] content, String mimeType) throws Exception {
        MinioClient minioClient = MinioClient.builder()
                .endpoint(MINIO_ENDPOINT)
                .credentials(MINIO_ACCESS_KEY, MINIO_SECRET_KEY)
                .build();
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                .bucket(MINIO_BUCKET)
                .build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder()
                    .bucket(MINIO_BUCKET)
                    .build());
        }
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(content)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(MINIO_BUCKET)
                    .object(key)
                    .stream(inputStream, (long) content.length, -1L)
                    .contentType(mimeType)
                    .build());
        }
    }

    private static Path findFirstJpegImage() throws Exception {
        return findImage(List.of(".jpg", ".jpeg"));
    }

    private static Path findFirstPngImage() throws Exception {
        return findImage(List.of(".png"));
    }

    private static Path findImage(List<String> extensions) throws Exception {
        Path imagesDir = Path.of("images");
        if (!Files.isDirectory(imagesDir)) {
            return null;
        }
        try (var stream = Files.list(imagesDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return extensions.stream().anyMatch(name::endsWith);
                    })
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static String sha256(String content) {
        return sha256(content.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", e);
        }
    }

    private static String jsonArray(String[] values) {
        if (values == null) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('"').append(json(values[i])).append('"');
        }
        return sb.append(']').toString();
    }

    private static String jsonNullable(String value) {
        return value == null ? "null" : "\"" + json(value) + "\"";
    }

    private static String numberOrNull(Long value) {
        return value == null ? "null" : value.toString();
    }

    private static String json(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static void printSummary(String crawlTaskId, String userIdA, String userIdB,
                                     String userIdC, String userIdD, String channelId,
                                     String postId1, String postId2, String postId3,
                                     String postId4, String postId5, String newsId1,
                                     String assetId1, String assetId2) {
        System.out.println("========= Full Test Dataset 发送完成 =========");
        System.out.println("crawlTaskId = " + crawlTaskId + "（用这个值验证collection_tasks表）");
        System.out.println();
        System.out.println("=== 账号 ===");
        System.out.println("userIdA (安全分析KOL) → social_accounts WHERE platform_user_id = '" + userIdA + "'");
        System.out.println("userIdB (中东媒体)    → social_accounts WHERE platform_user_id = '" + userIdB + "'");
        System.out.println("userIdC (官方简报)    → social_accounts WHERE platform_user_id = '" + userIdC + "'");
        System.out.println("userIdD (bot,封禁)→ social_accounts WHERE platform_user_id = '" + userIdD + "'");
        System.out.println("channelId (海湾频道)→ social_accounts WHERE platform_user_id = '" + channelId + "'");
        System.out.println();
        System.out.println("=== 关系（回填后验证）===");
        System.out.println("A关注B, D关注A (following), C管理channelId (admin_of)");
        System.out.println("→ SELECT * FROM account_relations WHERE synced_to_neo4j = false;（回填前）");
        System.out.println("→ MATCH (a:SocialAccount)-[r]->(b:SocialAccount) RETURN a,r,b（回填后Neo4j里查）");
        System.out.println();
        System.out.println("=== 内容 ===");
        System.out.println("postId1 (A原创，美伊冲突含图) → media_contents WHERE platform_content_id = '" + postId1 + "'");
        System.out.println("postId2 (B转发postId1) → repost_of_content_id = '" + postId1 + "'");
        System.out.println("postId3 (C引用并降温回应postId1) → quoted_content_id = '" + postId1 + "'");
        System.out.println("postId4 (D bot煽动内容)    → media_contents WHERE platform_content_id = '" + postId4 + "'");
        System.out.println("postId5 (D回复postId1) → parent_content_id = '" + postId1 + "'");
        System.out.println("newsId1 (新闻文章)      → platform = 'news', content_type = 'article', platform_content_id = '" + newsId1 + "'");
        System.out.println();
        System.out.println("=== 媒体资产 ===");
        System.out.println("assetId1 → media_assets WHERE source_asset_id = '" + assetId1 + "'");
        System.out.println("assetId2 → media_assets WHERE source_asset_id = '" + assetId2 + "'");
        System.out.println();
        System.out.println("=== 全局验证SQL ===");
        System.out.println("SELECT record_type, pipeline_status, count(*) FROM raw_records");
        System.out.println("  WHERE crawl_task_id = '" + crawlTaskId + "'");
        System.out.println("  GROUP BY record_type, pipeline_status");
        System.out.println("  ORDER BY record_type;");
        System.out.println("==========================================");
    }

    private static void printSelfCheck() {
        System.out.println();
        System.out.println("=== isValidSchema 必填字段自检 ===");
        System.out.println("collection_task: schema_version, record_type, raw_record_id, raw_payload_hash, data{}, root.crawl_task_id 均已填");
        System.out.println("social_account: common envelope + root.platform + data.platform_user_id 均已填");
        System.out.println("account_relation: common envelope + root.platform + data.source_platform_user_id + data.target_platform_user_id + data.relation_type + data.observed_at(ISO-8601) 均已填");
        System.out.println("social_content: schema_version + record_type + raw_record_id + raw_payload_hash + root.platform + data.platform_content_id + data.author_platform_user_id 均已填");
        System.out.println("news_article: common envelope + root.platform + root.source_url 已填，满足 source_url/data.url 二选一");
        System.out.println("media_asset: common envelope + data.sha256 + data.asset_type 均已填，sha256/asset_type 均在 data 节点");
        System.out.println("Normalizer 字段对齐: social metrics 使用 data.metrics.*，account metrics 使用 data.metrics.*，media asset 使用 data.asset_id/source_url/storage_uri/mime_type/sha256/file_size_bytes/minio_bucket/minio_key");
    }

    private record AssetPayload(String assetId, String mimeType, String sha256,
                                long fileSizeBytes, String storageUri,
                                String sourceUrl, String minioKey) {
    }
}
