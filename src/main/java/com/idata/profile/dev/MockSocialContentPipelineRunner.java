package com.idata.profile.dev;

import com.idata.profile.common.constant.PipelineStatus;
import com.idata.profile.common.util.HashUtil;
import com.idata.profile.entity.raw.RawRecord;
import com.idata.profile.ingestion.consumer.SocialContentConsumer;
import com.idata.profile.mapper.raw.RawRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Slf4j
@Component
@Profile("mock")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dev.mock-social-content-runner.enabled", havingValue = "true")
public class MockSocialContentPipelineRunner implements ApplicationRunner {

    private static final int MAX_ATTEMPTS = 60;
    private static final long POLL_INTERVAL_MS = 1000L;

    private final SocialContentConsumer socialContentConsumer;
    private final RawRecordMapper rawRecordMapper;
    private final Environment environment;
    private final ConfigurableApplicationContext applicationContext;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String uniqueId = UUID.randomUUID().toString().replace("-", "");
        String sourceRecordId = "mock_social_content_" + uniqueId;
        String platformContentId = "mock_post_" + uniqueId;
        String message = buildMessage(sourceRecordId, platformContentId);

        log.info("Triggering mock social_content pipeline, sourceRecordId={}", sourceRecordId);
        socialContentConsumer.onMessage(message);

        RawRecord finalRecord = waitForT4Indexed(sourceRecordId);
        log.info("Mock social_content pipeline completed, sourceRecordId={}, rawRecordId={}, status={}",
                sourceRecordId, finalRecord.getId(), finalRecord.getPipelineStatus());

        if (environment.getProperty("dev.mock-social-content-runner.exit-on-complete", Boolean.class, false)) {
            int exitCode = SpringApplication.exit(applicationContext, () -> 0);
            System.exit(exitCode);
        }
    }

    private RawRecord waitForT4Indexed(String sourceRecordId) throws InterruptedException {
        RawRecord lastRecord = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            RawRecord record = rawRecordMapper.selectFullTraceBySourceRecordId(sourceRecordId);
            if (record != null) {
                lastRecord = record;
                String status = record.getPipelineStatus();
                log.info("Mock social_content pipeline poll {}/{}, status={}", attempt, MAX_ATTEMPTS, status);
                if (PipelineStatus.T4_INDEXED.name().equals(status)) {
                    return record;
                }
                if (PipelineStatus.FAILED.name().equals(status)) {
                    throw new IllegalStateException("Mock social_content pipeline failed: " + sourceRecordId);
                }
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }

        String lastStatus = lastRecord == null ? "NOT_INSERTED" : lastRecord.getPipelineStatus();
        throw new IllegalStateException("Timed out waiting for T4_INDEXED, sourceRecordId="
                + sourceRecordId + ", lastStatus=" + lastStatus);
    }

    private String buildMessage(String sourceRecordId, String platformContentId) {
        String now = OffsetDateTime.now(ZoneOffset.UTC).toString();
        String payloadHash = HashUtil.sha256(sourceRecordId);
        return """
                {
                  "schema_version": "kt3_to_kt4_v1",
                  "record_type": "social_content",
                  "raw_record_id": "%s",
                  "raw_payload_hash": "sha256:%s",
                  "crawl_task_id": "mock_pipeline_check",
                  "collected_at": "%s",
                  "source_url": "https://mock.local/social/%s",
                  "platform": "mock",
                  "language": "zh-CN",
                  "data": {
                    "content_type": "post",
                    "platform_content_id": "%s",
                    "author_platform_user_id": "mock_author_001",
                    "text": "这是一条用于验证 social_content 消费链路的 mock 消息，需要完整跑完 T1 T2 T3 T4。",
                    "published_at": "%s",
                    "url": "https://mock.local/social/%s",
                    "hashtags": ["mock", "pipeline"],
                    "mentions": ["mock_author_001"],
                    "external_urls": ["https://mock.local/reference"],
                    "like_count": 12,
                    "comment_count": 3,
                    "share_count": 2,
                    "view_count": 120
                  }
                }
                """.formatted(sourceRecordId, payloadHash, now, platformContentId,
                platformContentId, now, platformContentId);
    }
}
