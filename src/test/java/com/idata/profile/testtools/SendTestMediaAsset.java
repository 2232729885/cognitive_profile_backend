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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
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
import java.util.concurrent.ExecutionException;

public class SendTestMediaAsset {

    private static final String BOOTSTRAP_SERVERS = "172.16.40.232:9092";
    private static final String MINIO_ENDPOINT = "http://172.16.40.232:9000";
    private static final String MINIO_ACCESS_KEY = "minioadmin";
    private static final String MINIO_SECRET_KEY = "minioadmin";
    private static final String BUCKET = "media-assets";
    private static final String SCHEMA_VERSION = "kt3_to_kt4_v1";
    private static final String RECORD_TYPE = "media_asset";
    private static final String PLATFORM = "local";

    public static void main(String[] args) throws Exception {
        Path imagesDir = Path.of("images");
        if (!Files.isDirectory(imagesDir)) {
            throw new IllegalStateException("images directory not found: " + imagesDir.toAbsolutePath());
        }

        MinioClient minioClient = MinioClient.builder()
                .endpoint(MINIO_ENDPOINT)
                .credentials(MINIO_ACCESS_KEY, MINIO_SECRET_KEY)
                .build();
        ensureBucket(minioClient);

        List<Path> imageFiles;
        try (var stream = Files.list(imagesDir)) {
            imageFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(SendTestMediaAsset::isSupportedImage)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }

        if (imageFiles.isEmpty()) {
            System.out.println("No image files found under " + imagesDir.toAbsolutePath());
            return;
        }

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            for (Path imageFile : imageFiles) {
                sendImage(minioClient, producer, imageFile);
            }
            producer.flush();
        }
    }

    private static void sendImage(
            MinioClient minioClient,
            KafkaProducer<String, String> producer,
            Path imageFile
    ) throws IOException, ExecutionException, InterruptedException {
        String fileName = imageFile.getFileName().toString();
        String objectKey = "test/" + fileName;
        byte[] content = Files.readAllBytes(imageFile);
        String imageSha256 = sha256(content);
        String contentType = mimeType(fileName);
        ImageSize imageSize = readImageSize(content);

        upload(minioClient, objectKey, content, contentType);

        String sourceRecordId = UUID.randomUUID().toString();
        String sourceAssetId = UUID.randomUUID().toString();
        String now = OffsetDateTime.now(ZoneOffset.UTC).toString();
        String sourceUrl = MINIO_ENDPOINT + "/" + BUCKET + "/" + urlPath(objectKey);
        String storageUri = BUCKET + "/" + objectKey;

        String messageWithoutHash = buildMessage(
                sourceRecordId,
                "",
                sourceAssetId,
                now,
                imageSha256,
                sourceUrl,
                storageUri,
                objectKey,
                contentType,
                content.length,
                imageSize.width(),
                imageSize.height()
        );
        String rawPayloadHash = sha256(messageWithoutHash.getBytes(StandardCharsets.UTF_8));
        String message = buildMessage(
                sourceRecordId,
                rawPayloadHash,
                sourceAssetId,
                now,
                imageSha256,
                sourceUrl,
                storageUri,
                objectKey,
                contentType,
                content.length,
                imageSize.width(),
                imageSize.height()
        );

        ProducerRecord<String, String> record = new ProducerRecord<>(
                KafkaTopicConstants.MEDIA_ASSET,
                sourceRecordId,
                message
        );
        RecordMetadata metadata = producer.send(record).get();

        System.out.println("file=" + fileName
                + ", sha256=" + imageSha256
                + ", minioPath=" + storageUri
                + ", kafka=" + metadata.topic() + "/" + metadata.partition() + "/" + metadata.offset());
    }

    private static void ensureBucket(MinioClient minioClient) {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                    .bucket(BUCKET)
                    .build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder()
                        .bucket(BUCKET)
                        .build());
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to ensure MinIO bucket: " + BUCKET, e);
        }
    }

    private static void upload(MinioClient minioClient, String objectKey, byte[] content, String contentType) {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(content)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(BUCKET)
                    .object(objectKey)
                    .stream(inputStream, (long) content.length, -1L)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload image to MinIO: " + BUCKET + "/" + objectKey, e);
        }
    }

    private static String buildMessage(
            String sourceRecordId,
            String rawPayloadHash,
            String sourceAssetId,
            String now,
            String imageSha256,
            String sourceUrl,
            String storageUri,
            String minioKey,
            String contentType,
            long fileSizeBytes,
            Integer width,
            Integer height
    ) {
        return String.format("""
                {
                  "schema_version": "%s",
                  "record_type": "%s",
                  "platform": "%s",
                  "raw_record_id": "%s",
                  "raw_payload_hash": "%s",
                  "collected_at": "%s",
                  "data": {
                    "asset_id": "%s",
                    "asset_type": "image",
                    "source_url": "%s",
                    "storage_uri": "%s",
                    "minio_bucket": "%s",
                    "minio_key": "%s",
                    "mime_type": "%s",
                    "sha256": "%s",
                    "file_size_bytes": %d,
                    "width": %s,
                    "height": %s,
                    "thumbnail_uri": null,
                    "ocr_text": null,
                    "asr_text": null
                  }
                }
                """,
                json(SCHEMA_VERSION),
                json(RECORD_TYPE),
                json(PLATFORM),
                json(sourceRecordId),
                json(rawPayloadHash),
                json(now),
                json(sourceAssetId),
                json(sourceUrl),
                json(storageUri),
                json(BUCKET),
                json(minioKey),
                json(contentType),
                json(imageSha256),
                fileSizeBytes,
                jsonNumber(width),
                jsonNumber(height)
        );
    }

    private static boolean isSupportedImage(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jpg")
                || name.endsWith(".jpeg")
                || name.endsWith(".png")
                || name.endsWith(".gif");
    }

    private static String mimeType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        return "image/jpeg";
    }

    private static ImageSize readImageSize(byte[] content) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(content));
        if (image == null) {
            return new ImageSize(null, null);
        }
        return new ImageSize(image.getWidth(), image.getHeight());
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

    private static String urlPath(String path) {
        StringBuilder encoded = new StringBuilder(path.length());
        for (char c : path.toCharArray()) {
            if (isUrlPathSafe(c)) {
                encoded.append(c);
            } else {
                byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    encoded.append('%').append(String.format("%02X", b));
                }
            }
        }
        return encoded.toString();
    }

    private static boolean isUrlPathSafe(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '/'
                || c == '.'
                || c == '-'
                || c == '_'
                || c == '~';
    }

    private static String jsonNumber(Integer value) {
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

    private record ImageSize(Integer width, Integer height) {
    }
}
