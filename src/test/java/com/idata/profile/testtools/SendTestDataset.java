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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

public class SendTestDataset {

    private static final String BOOTSTRAP_SERVERS = "172.16.40.232:9092";
    private static final String MINIO_ENDPOINT = "http://172.16.40.232:9000";
    private static final String MINIO_ACCESS_KEY = "minioadmin";
    private static final String MINIO_SECRET_KEY = "minioadmin";
    private static final String MINIO_BUCKET = "media-assets";
    private static final String SCHEMA_VERSION = "kt3_to_kt4_v1";

    public static void main(String[] args) throws Exception {
        String crawlTaskId = "dataset_crawl_" + UUID.randomUUID();
        String platformUserIdA = "dataset_user_" + UUID.randomUUID();
        String platformUserIdB = "dataset_user_" + UUID.randomUUID();
        String contentId1 = "dataset_content_" + UUID.randomUUID();
        String contentId2 = "dataset_content_" + UUID.randomUUID();
        String assetId1 = "dataset_asset_" + UUID.randomUUID();
        String newsArticleId = "dataset_news_" + UUID.randomUUID();
        String platform = "x";

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime windowStart = now.minusDays(7);
        MediaAssetPayload assetPayload = prepareMediaAsset(assetId1);

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            send(producer, KafkaTopicConstants.COLLECTION_TASK, collectionTaskMessage(
                    crawlTaskId, platform, now, windowStart));

            send(producer, KafkaTopicConstants.SOCIAL_ACCOUNT, socialAccountMessage(
                    crawlTaskId, platform, platformUserIdA, "@dataset_user_a",
                    "Dataset User A", true, "blue", 12_500, 380, 2_100, now));
            send(producer, KafkaTopicConstants.SOCIAL_ACCOUNT, socialAccountMessage(
                    crawlTaskId, platform, platformUserIdB, "@dataset_user_b",
                    "Dataset User B", false, null, 8_300, 420, 1_580, now));

            Thread.sleep(1_000L);

            send(producer, KafkaTopicConstants.ACCOUNT_RELATION, accountRelationMessage(
                    crawlTaskId, platform, platformUserIdA, platformUserIdB, now));

            send(producer, KafkaTopicConstants.SOCIAL_CONTENT, socialContent1Message(
                    crawlTaskId, platform, platformUserIdA, contentId1, now));
            send(producer, KafkaTopicConstants.SOCIAL_CONTENT, socialContent2Message(
                    crawlTaskId, platform, platformUserIdB, contentId1, contentId2, now));

            send(producer, KafkaTopicConstants.NEWS_ARTICLE, newsArticleMessage(
                    crawlTaskId, platform, newsArticleId, contentId1, now));

            Thread.sleep(2_000L);

            send(producer, KafkaTopicConstants.MEDIA_ASSET, mediaAssetMessage(
                    crawlTaskId, platform, contentId1, assetPayload, now));

            producer.flush();
        }

        printSummary(crawlTaskId, platformUserIdA, platformUserIdB, contentId1, contentId2, assetId1, newsArticleId);
    }

    private static void send(KafkaProducer<String, String> producer, String topic, String messageWithoutHash)
            throws Exception {
        String rawRecordId = UUID.randomUUID().toString();
        String withRawRecordId = messageWithoutHash.replace("${raw_record_id}", json(rawRecordId));
        String payloadHash = sha256(withRawRecordId.getBytes(StandardCharsets.UTF_8));
        String message = withRawRecordId.replace("${raw_payload_hash}", payloadHash);

        RecordMetadata metadata = producer.send(new ProducerRecord<>(topic, rawRecordId, message)).get();
        System.out.println("sent topic=" + metadata.topic()
                + ", partition=" + metadata.partition()
                + ", offset=" + metadata.offset()
                + ", rawRecordId=" + rawRecordId);
    }

    private static String collectionTaskMessage(String crawlTaskId, String platform,
                                                OffsetDateTime now, OffsetDateTime windowStart) {
        return """
                {
                  "schema_version": "%s",
                  "record_type": "collection_task",
                  "raw_record_id": "${raw_record_id}",
                  "raw_payload_hash": "${raw_payload_hash}",
                  "crawl_task_id": "%s",
                  "collected_at": "%s",
                  "platform": "%s",
                  "language": "zh",
                  "data": {
                    "collection_method": "api",
                    "seed_type": "keyword",
                    "seed_value": "选举 外部干预",
                    "query_expression": "选举 外部干预",
                    "time_window_start": "%s",
                    "time_window_end": "%s",
                    "target_languages": ["zh", "en"],
                    "target_regions": ["US", "CN"],
                    "collector_version": "v1.0.0",
                    "records_collected": 3
                  }
                }
                """.formatted(
                json(SCHEMA_VERSION),
                json(crawlTaskId),
                json(now.toString()),
                json(platform),
                json(windowStart.toString()),
                json(now.toString()));
    }

    private static String socialAccountMessage(String crawlTaskId, String platform, String platformUserId,
                                               String handle, String displayName, boolean verified,
                                               String verifiedType, long followers, long following,
                                               long posts, OffsetDateTime now) {
        return """
                {
                  "schema_version": "%s",
                  "record_type": "social_account",
                  "raw_record_id": "${raw_record_id}",
                  "raw_payload_hash": "${raw_payload_hash}",
                  "crawl_task_id": "%s",
                  "collected_at": "%s",
                  "platform": "%s",
                  "language": "zh",
                  "data": {
                    "platform_user_id": "%s",
                    "account_entity_type": "user",
                    "handle": "%s",
                    "display_name": "%s",
                    "verified": %s,
                    "verified_type": %s,
                    "metrics": {
                      "followers_count": %d,
                      "following_count": %d,
                      "post_count": %d
                    }
                  }
                }
                """.formatted(
                json(SCHEMA_VERSION),
                json(crawlTaskId),
                json(now.toString()),
                json(platform),
                json(platformUserId),
                json(handle),
                json(displayName),
                verified,
                jsonNullable(verifiedType),
                followers,
                following,
                posts);
    }

    private static String accountRelationMessage(String crawlTaskId, String platform,
                                                 String platformUserIdA, String platformUserIdB,
                                                 OffsetDateTime now) {
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
                    "relation_type": "following",
                    "observed_at": "%s",
                    "occurred_at": null,
                    "source": "following_list"
                  }
                }
                """.formatted(
                json(SCHEMA_VERSION),
                json(crawlTaskId),
                json(now.toString()),
                json(platform),
                json(platformUserIdA),
                json(platformUserIdB),
                json(now.toString()));
    }

    private static String socialContent1Message(String crawlTaskId, String platform,
                                                String platformUserIdA, String contentId1,
                                                OffsetDateTime now) {
        return """
                {
                  "schema_version": "%s",
                  "record_type": "social_content",
                  "raw_record_id": "${raw_record_id}",
                  "raw_payload_hash": "${raw_payload_hash}",
                  "crawl_task_id": "%s",
                  "collected_at": "%s",
                  "platform": "%s",
                  "language": "zh",
                  "data": {
                    "platform_content_id": "%s",
                    "author_platform_user_id": "%s",
                    "content_type": "post",
                    "body_text": "外部势力干预选举的证据越来越多，媒体却集体沉默。#选举 #外部干预",
                    "published_at": "%s",
                    "url": "https://x.example/%s/status/%s",
                    "hashtags": ["选举", "外部干预"],
                    "root_content_id": "%s",
                    "metrics": {
                      "like_count": 342,
                      "repost_count": 128,
                      "view_count": 15600
                    }
                  }
                }
                """.formatted(
                json(SCHEMA_VERSION),
                json(crawlTaskId),
                json(now.toString()),
                json(platform),
                json(contentId1),
                json(platformUserIdA),
                json(now.toString()),
                json(platformUserIdA),
                json(contentId1),
                json(contentId1));
    }

    private static String socialContent2Message(String crawlTaskId, String platform,
                                                String platformUserIdB, String contentId1,
                                                String contentId2, OffsetDateTime now) {
        return """
                {
                  "schema_version": "%s",
                  "record_type": "social_content",
                  "raw_record_id": "${raw_record_id}",
                  "raw_payload_hash": "${raw_payload_hash}",
                  "crawl_task_id": "%s",
                  "collected_at": "%s",
                  "platform": "%s",
                  "language": "zh",
                  "data": {
                    "platform_content_id": "%s",
                    "author_platform_user_id": "%s",
                    "content_type": "quote",
                    "body_text": "这个说法有待商榷，需要更多证据。#选举",
                    "published_at": "%s",
                    "url": "https://x.example/%s/status/%s",
                    "quoted_content_id": "%s",
                    "root_content_id": "%s",
                    "hashtags": ["选举"]
                  }
                }
                """.formatted(
                json(SCHEMA_VERSION),
                json(crawlTaskId),
                json(now.toString()),
                json(platform),
                json(contentId2),
                json(platformUserIdB),
                json(now.toString()),
                json(platformUserIdB),
                json(contentId2),
                json(contentId1),
                json(contentId1));
    }

    private static String newsArticleMessage(String crawlTaskId, String platform, String newsArticleId,
                                             String contentId1, OffsetDateTime now) {
        String articleUrl = "https://news.example/articles/" + newsArticleId;
        return """
                {
                  "schema_version": "%s",
                  "record_type": "news_article",
                  "raw_record_id": "${raw_record_id}",
                  "raw_payload_hash": "${raw_payload_hash}",
                  "crawl_task_id": "%s",
                  "collected_at": "%s",
                  "platform": "%s",
                  "language": "zh",
                  "source_url": "%s",
                  "data": {
                    "platform_content_id": "%s",
                    "title": "Dataset News Article",
                    "body_text": "新闻报道引用了社交内容 %s，并讨论选举与外部干预叙事。",
                    "published_at": "%s",
                    "url": "%s",
                    "source_name": "Dataset News",
                    "domain": "news.example",
                    "author": "Dataset Reporter",
                    "section": "politics",
                    "tags": ["选举", "外部干预"],
                    "related_platform_content_id": "%s"
                  }
                }
                """.formatted(
                json(SCHEMA_VERSION),
                json(crawlTaskId),
                json(now.toString()),
                json(platform),
                json(articleUrl),
                json(newsArticleId),
                json(contentId1),
                json(now.toString()),
                json(articleUrl),
                json(contentId1));
    }

    private static String mediaAssetMessage(String crawlTaskId, String platform, String contentId1,
                                            MediaAssetPayload payload, OffsetDateTime now) {
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
                """.formatted(
                json(SCHEMA_VERSION),
                json(crawlTaskId),
                json(now.toString()),
                json(platform),
                json(payload.assetId()),
                json(contentId1),
                json(payload.sourceUrl()),
                json(payload.storageUri()),
                json(payload.mimeType()),
                json(payload.sha256()),
                payload.fileSizeBytes(),
                json(MINIO_BUCKET),
                json(payload.minioKey()));
    }

    private static MediaAssetPayload prepareMediaAsset(String assetId) throws Exception {
        Path image = firstImage();
        if (image == null) {
            String minioKey = "test/placeholder.jpg";
            return new MediaAssetPayload(
                    assetId,
                    "image/jpeg",
                    UUID.randomUUID().toString(),
                    "media-assets/" + minioKey,
                    MINIO_ENDPOINT + "/" + MINIO_BUCKET + "/" + minioKey,
                    minioKey,
                    0L);
        }

        byte[] content = Files.readAllBytes(image);
        String extension = extension(image.getFileName().toString());
        String minioKey = "dataset/" + assetId + extension;
        String mimeType = mimeType(extension);
        uploadToMinio(minioKey, content, mimeType);
        return new MediaAssetPayload(
                assetId,
                mimeType,
                sha256(content),
                MINIO_BUCKET + "/" + minioKey,
                MINIO_ENDPOINT + "/" + MINIO_BUCKET + "/" + minioKey,
                minioKey,
                content.length);
    }

    private static Path firstImage() throws Exception {
        Path imagesDir = Path.of("images");
        if (!Files.isDirectory(imagesDir)) {
            return null;
        }
        try (var stream = Files.list(imagesDir)) {
            List<Path> images = stream
                    .filter(Files::isRegularFile)
                    .filter(SendTestDataset::isSupportedImage)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            return images.isEmpty() ? null : images.getFirst();
        }
    }

    private static void uploadToMinio(String minioKey, byte[] content, String mimeType) {
        try {
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
                        .object(minioKey)
                        .stream(inputStream, (long) content.length, -1L)
                        .contentType(mimeType)
                        .build());
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload dataset media asset to MinIO", e);
        }
    }

    private static boolean isSupportedImage(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jpg")
                || name.endsWith(".jpeg")
                || name.endsWith(".png")
                || name.endsWith(".gif");
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot).toLowerCase(Locale.ROOT) : ".jpg";
    }

    private static String mimeType(String extension) {
        return switch (extension) {
            case ".png" -> "image/png";
            case ".gif" -> "image/gif";
            default -> "image/jpeg";
        };
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

    private static String jsonNullable(String value) {
        return value == null ? "null" : "\"" + json(value) + "\"";
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

    private static void printSummary(String crawlTaskId, String platformUserIdA, String platformUserIdB,
                                     String contentId1, String contentId2, String assetId1,
                                     String newsArticleId) {
        System.out.println("========= 测试数据集关联关系摘要 =========");
        System.out.println("crawlTaskId     = " + crawlTaskId);
        System.out.println("platformUserIdA = " + platformUserIdA + "   -> kt3.social_account");
        System.out.println("platformUserIdB = " + platformUserIdB + "   -> kt3.social_account");
        System.out.println("A 关注 B        -> kt3.account_relation");
        System.out.println("contentId1      = " + contentId1 + "   -> kt3.social_content（A发的）");
        System.out.println("contentId2      = " + contentId2 + "   -> kt3.social_content（B引用了contentId1）");
        System.out.println("assetId1        = " + assetId1 + "   -> kt3.media_asset（内容1的配图）");
        System.out.println("newsArticleId   = " + newsArticleId + "   -> kt3.news_article");
        System.out.println("========================================");
        System.out.println("验证SQL: SELECT source_record_id, pipeline_status FROM raw_records");
        System.out.println("         WHERE crawl_task_id = '" + crawlTaskId + "' ORDER BY created_at;");
    }

    private record MediaAssetPayload(String assetId, String mimeType, String sha256,
                                     String storageUri, String sourceUrl, String minioKey,
                                     long fileSizeBytes) {
    }
}
