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

public class SendRealWorldDataset {

    private static final String BOOTSTRAP_SERVERS = "172.16.40.232:9092";
    private static final String MINIO_ENDPOINT = "http://172.16.40.232:9000";
    private static final String MINIO_ACCESS_KEY = "minioadmin";
    private static final String MINIO_SECRET_KEY = "minioadmin";
    private static final String MINIO_BUCKET = "media-assets";
    private static final String SCHEMA_VERSION = "kt3_to_kt4_v1";
    private static final int RECORDS_COLLECTED = 31;

    public static void main(String[] args) throws Exception {
        String crawlTaskId = "real_world_" + UUID.randomUUID();

        String pressTvId = "rw_presstv_" + UUID.randomUUID();
        String irgcBotId = "rw_irgc_amp_" + UUID.randomUUID();
        String telegramChannelId = "rw_tg_resistance_" + UUID.randomUUID();
        String israelMfaId = "rw_israel_mfa_" + UUID.randomUUID();
        String bbcPersianId = "rw_bbc_persian_" + UUID.randomUUID();
        String alMayadeenId = "rw_almayadeen_" + UUID.randomUUID();
        String vietnamMediaId = "rw_vietnamplus_" + UUID.randomUUID();
        String manilaPageId = "rw_manila_watch_" + UUID.randomUUID();
        String indonesiaPageId = "rw_nusantara_mena_" + UUID.randomUUID();
        String iranianCivilId = "rw_tehran_civil_" + UUID.randomUUID();

        String postId1 = "rw_post_presstv_" + UUID.randomUUID();
        String postId2 = "rw_post_ar_repost_" + UUID.randomUUID();
        String postId3 = "rw_post_fa_quote_" + UUID.randomUUID();
        String postId4 = "rw_post_irgc_bot_" + UUID.randomUUID();
        String postId5 = "rw_post_vi_quote_" + UUID.randomUUID();
        String postId6 = "rw_post_tl_quote_" + UUID.randomUUID();
        String postId7 = "rw_post_israel_" + UUID.randomUUID();
        String postId8 = "rw_post_tg_channel_" + UUID.randomUUID();
        String postId9 = "rw_post_zh_report_" + UUID.randomUUID();
        String postId10 = "rw_post_zh_reply_" + UUID.randomUUID();
        String postId11 = "rw_post_id_quote_" + UUID.randomUUID();
        String postId12 = "rw_post_bbc_fa_" + UUID.randomUUID();

        String newsId1 = "rw_news_bbc_" + UUID.randomUUID();
        String newsId2 = "rw_news_presstv_" + UUID.randomUUID();
        String newsId3 = "rw_news_vnexpress_" + UUID.randomUUID();

        String assetId1 = "rw_asset_gulf_" + UUID.randomUUID();
        String assetId2 = "rw_asset_news_" + UUID.randomUUID();

        Instant now = Instant.now();
        AssetPayload asset1 = prepareAsset(
                assetId1, "test/real_world/gulf-map.jpg", "image/jpeg", findFirstJpegImage(), true);
        AssetPayload asset2 = prepareAsset(
                assetId2, "test/real_world/news-brief.png", "image/png", findFirstPngImage(), false);

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            send(producer, KafkaTopicConstants.COLLECTION_TASK, collectionTaskKeyword(crawlTaskId, now));
            send(producer, KafkaTopicConstants.COLLECTION_TASK, collectionTaskAccount(crawlTaskId, now));

            send(producer, KafkaTopicConstants.SOCIAL_ACCOUNT, accountPressTv(crawlTaskId, pressTvId, now));
            send(producer, KafkaTopicConstants.SOCIAL_ACCOUNT, accountIrgcBot(crawlTaskId, irgcBotId, now));
            send(producer, KafkaTopicConstants.SOCIAL_ACCOUNT, accountTelegramChannel(crawlTaskId, telegramChannelId, now));
            send(producer, KafkaTopicConstants.SOCIAL_ACCOUNT, accountIsraelMfa(crawlTaskId, israelMfaId, now));
            send(producer, KafkaTopicConstants.SOCIAL_ACCOUNT, accountBbcPersian(crawlTaskId, bbcPersianId, now));
            send(producer, KafkaTopicConstants.SOCIAL_ACCOUNT, accountAlMayadeen(crawlTaskId, alMayadeenId, now));
            send(producer, KafkaTopicConstants.SOCIAL_ACCOUNT, accountVietnamMedia(crawlTaskId, vietnamMediaId, now));
            send(producer, KafkaTopicConstants.SOCIAL_ACCOUNT, accountManilaPage(crawlTaskId, manilaPageId, now));
            send(producer, KafkaTopicConstants.SOCIAL_ACCOUNT, accountIndonesiaPage(crawlTaskId, indonesiaPageId, now));
            send(producer, KafkaTopicConstants.SOCIAL_ACCOUNT, accountIranianCivil(crawlTaskId, iranianCivilId, now));

            Thread.sleep(1_000L);

            send(producer, KafkaTopicConstants.ACCOUNT_RELATION, accountRelation(
                    crawlTaskId, "x", irgcBotId, pressTvId, "following", "following_list", now));
            send(producer, KafkaTopicConstants.ACCOUNT_RELATION, accountRelation(
                    crawlTaskId, "x", iranianCivilId, pressTvId, "following", "following_list", now));
            send(producer, KafkaTopicConstants.ACCOUNT_RELATION, accountRelation(
                    crawlTaskId, "x", vietnamMediaId, bbcPersianId, "following", "media_monitoring", now));
            send(producer, KafkaTopicConstants.ACCOUNT_RELATION, accountRelation(
                    crawlTaskId, "telegram", irgcBotId, telegramChannelId, "member_of", "telegram_channel_members", now));
            send(producer, KafkaTopicConstants.ACCOUNT_RELATION, accountRelation(
                    crawlTaskId, "telegram", pressTvId, telegramChannelId, "admin_of", "channel_profile", now));

            send(producer, KafkaTopicConstants.SOCIAL_CONTENT,
                    contentPressTvPost(crawlTaskId, pressTvId, postId1, assetId1, now));
            send(producer, KafkaTopicConstants.SOCIAL_CONTENT, contentArabicRepost(crawlTaskId, alMayadeenId, pressTvId, postId1, postId2, now));
            send(producer, KafkaTopicConstants.SOCIAL_CONTENT, contentPersianQuote(crawlTaskId, iranianCivilId, pressTvId, postId1, postId3, now));
            send(producer, KafkaTopicConstants.SOCIAL_CONTENT, contentIrgcBot(crawlTaskId, irgcBotId, pressTvId, postId4, now));
            send(producer, KafkaTopicConstants.SOCIAL_CONTENT, contentVietnamQuote(crawlTaskId, vietnamMediaId, postId1, postId3, postId5, now));
            send(producer, KafkaTopicConstants.SOCIAL_CONTENT, contentTagalogQuote(crawlTaskId, manilaPageId, postId1, postId3, postId6, now));
            send(producer, KafkaTopicConstants.SOCIAL_CONTENT, contentIsraelResponse(crawlTaskId, israelMfaId, pressTvId, postId7, now));
            send(producer, KafkaTopicConstants.SOCIAL_CONTENT, contentTelegramChannel(crawlTaskId, telegramChannelId, postId8, now));
            send(producer, KafkaTopicConstants.SOCIAL_CONTENT, contentChineseReport(crawlTaskId, pressTvId, postId1, postId9, now));
            send(producer, KafkaTopicConstants.SOCIAL_CONTENT, contentChineseReply(crawlTaskId, iranianCivilId, postId1, postId9, postId10, now));
            send(producer, KafkaTopicConstants.SOCIAL_CONTENT, contentIndonesianQuote(crawlTaskId, indonesiaPageId, postId1, postId3, postId11, now));
            send(producer, KafkaTopicConstants.SOCIAL_CONTENT, contentBbcPersianPost(crawlTaskId, bbcPersianId, postId12, now));

            send(producer, KafkaTopicConstants.NEWS_ARTICLE, newsBbc(crawlTaskId, newsId1, now));
            send(producer, KafkaTopicConstants.NEWS_ARTICLE, newsPressTv(crawlTaskId, newsId2, now));
            send(producer, KafkaTopicConstants.NEWS_ARTICLE, newsVnExpress(crawlTaskId, newsId3, now));

            Thread.sleep(2_000L);

            send(producer, KafkaTopicConstants.MEDIA_ASSET, mediaAsset(crawlTaskId, "x", "en", postId1, asset1, now));
            send(producer, KafkaTopicConstants.MEDIA_ASSET, mediaAsset(crawlTaskId, "news", "en", newsId1, asset2, now));
            producer.flush();
        }

        printSummary(crawlTaskId, postId1, postId2, postId3, postId5, postId6, assetId1);
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

    private static String collectionTaskKeyword(String crawlTaskId, Instant now) {
        return collectionTask(crawlTaskId, "keyword",
                "伊朗 美国 波斯湾 核 制裁 Iran Persian Gulf nuclear",
                "伊朗 美国 波斯湾 核 制裁 Iran Persian Gulf nuclear Hormuz cyberattack sanctions",
                "x", "zh",
                new String[]{"fa", "ar", "en", "zh", "vi", "tl", "id"},
                new String[]{"IR", "US", "IL", "SA", "AE", "VN", "PH", "ID"},
                now);
    }

    private static String collectionTaskAccount(String crawlTaskId, Instant now) {
        return collectionTask(crawlTaskId, "account",
                "presstv_official,irna_news,khamenei_ir,irgc_media_watch",
                "presstv_official OR irna_news OR khamenei_ir OR irgc_media_watch",
                "x", "en",
                new String[]{"fa", "ar", "en"},
                new String[]{"IR", "US", "IL"},
                now);
    }

    private static String collectionTask(String crawlTaskId, String seedType, String seedValue,
                                         String queryExpression, String platform, String language,
                                         String[] targetLanguages, String[] targetRegions, Instant now) {
        return """
                {
                  "schema_version": "%s",
                  "record_type": "collection_task",
                  "raw_record_id": "${raw_record_id}",
                  "raw_payload_hash": "${raw_payload_hash}",
                  "crawl_task_id": "%s",
                  "collected_at": "%s",
                  "platform": "%s",
                  "language": "%s",
                  "data": {
                    "collection_method": "api",
                    "seed_type": "%s",
                    "seed_value": "%s",
                    "query_expression": "%s",
                    "time_window_start": "%s",
                    "time_window_end": "%s",
                    "target_languages": %s,
                    "target_regions": %s,
                    "collector_version": "rw-2026.1",
                    "records_collected": %d
                  }
                }
                """.formatted(json(SCHEMA_VERSION), json(crawlTaskId), json(now.toString()),
                json(platform), json(language), json(seedType), json(seedValue), json(queryExpression),
                json(now.minus(7, ChronoUnit.DAYS).toString()), json(now.toString()),
                jsonArray(targetLanguages), jsonArray(targetRegions), RECORDS_COLLECTED);
    }

    private static String accountPressTv(String crawlTaskId, String id, Instant now) {
        return socialAccount(crawlTaskId, "x", "en", id, "user", null,
                "@PressTV_English", "Press TV English",
                "Iranian state-linked English-language news channel covering West Asia and global affairs.",
                true, "org", false, now.minus(5200, ChronoUnit.DAYS).toString(),
                3_400_000L, 420L, null, null, 128_000L, null, "Tehran, Iran", now);
    }

    private static String accountIrgcBot(String crawlTaskId, String id, Instant now) {
        return socialAccount(crawlTaskId, "x", "fa", id, "user", null,
                "@gulf_resistance_watch", "محور مقاومت فوری",
                "اخبار فوری مقاومت و خلیج فارس؛ حساب جایگزین پس از محدودیت‌های اخیر.",
                false, "none", true, now.minus(21, ChronoUnit.DAYS).toString(),
                6_800L, 4_900L, null, null, 2_300L, null, "Qom / Tehran", now);
    }

    private static String accountTelegramChannel(String crawlTaskId, String id, Instant now) {
        return socialAccount(crawlTaskId, "telegram", "fa", id, "channel", "telegram_channel",
                "sepah_field_updates", "اخبار میدانی سپاه",
                "کانال تلگرامی غیررسمی برای بازنشر بیانیه‌ها و ویدئوهای میدانی درباره خلیج فارس.",
                false, "none", false, now.minus(640, ChronoUnit.DAYS).toString(),
                null, null, null, 420_000L, 18_400L, 72_000_000L, "Iran", now);
    }

    private static String accountIsraelMfa(String crawlTaskId, String id, Instant now) {
        return socialAccount(crawlTaskId, "x", "en", id, "user", null,
                "@IsraelMFA", "Israel Foreign Ministry",
                "Official updates from Israel's Ministry of Foreign Affairs.",
                true, "government", false, now.minus(5000, ChronoUnit.DAYS).toString(),
                1_100_000L, 760L, null, null, 52_000L, null, "Jerusalem", now);
    }

    private static String accountBbcPersian(String crawlTaskId, String id, Instant now) {
        return socialAccount(crawlTaskId, "youtube", "fa", id, "channel", "youtube_channel",
                "BBCPersian", "BBC News فارسی",
                "گزارش و تحلیل ویدئویی درباره ایران، خاورمیانه و جهان.",
                true, "org", false, now.minus(4200, ChronoUnit.DAYS).toString(),
                null, null, 1_850_000L, null, 36_000L, 890_000_000L, "London, UK", now);
    }

    private static String accountAlMayadeen(String crawlTaskId, String id, Instant now) {
        return socialAccount(crawlTaskId, "x", "ar", id, "page", null,
                "@MayadeenGulf", "الميادين - الخليج",
                "متابعة عربية لتطورات الخليج وإيران والطاقة والممرات البحرية.",
                true, "blue", false, now.minus(3100, ChronoUnit.DAYS).toString(),
                780_000L, 900L, null, null, 64_000L, null, "Beirut", now);
    }

    private static String accountVietnamMedia(String crawlTaskId, String id, Instant now) {
        return socialAccount(crawlTaskId, "x", "vi", id, "page", null,
                "@VietnamPlus_MEA", "VietnamPlus Trung Đông",
                "Tin nhanh về Trung Đông, năng lượng và an ninh hàng hải.",
                true, "org", false, now.minus(2800, ChronoUnit.DAYS).toString(),
                320_000L, 280L, null, null, 41_000L, null, "Hà Nội", now);
    }

    private static String accountManilaPage(String crawlTaskId, String id, Instant now) {
        return socialAccount(crawlTaskId, "x", "tl", id, "page", null,
                "@ManilaGlobalWatch", "Manila Global Watch",
                "Balitang pandaigdig para sa mga Pilipino: enerhiya, OFW safety, at seguridad sa dagat.",
                true, "blue", false, now.minus(1500, ChronoUnit.DAYS).toString(),
                148_000L, 620L, null, null, 19_000L, null, "Manila", now);
    }

    private static String accountIndonesiaPage(String crawlTaskId, String id, Instant now) {
        return socialAccount(crawlTaskId, "x", "id", id, "page", null,
                "@NusantaraMENA", "Nusantara MENA Monitor",
                "Pemantauan Asia Barat untuk pembaca Indonesia: energi, diplomasi, dan keamanan pelayaran.",
                true, "org", false, now.minus(1900, ChronoUnit.DAYS).toString(),
                212_000L, 530L, null, null, 27_000L, null, "Jakarta", now);
    }

    private static String accountIranianCivil(String crawlTaskId, String id, Instant now) {
        return socialAccount(crawlTaskId, "x", "fa", id, "user", null,
                "@tehran_civic_note", "سارا از تهران",
                "صدای شهروندی؛ درباره زندگی روزمره، اقتصاد و نگرانی‌های مردم ایران می‌نویسم.",
                false, "none", false, now.minus(920, ChronoUnit.DAYS).toString(),
                18_700L, 830L, null, null, 4_100L, null, "Tehran", now);
    }

    private static String socialAccount(String crawlTaskId, String platform, String language,
                                        String platformUserId, String accountEntityType,
                                        String platformNativeType, String handle,
                                        String displayName, String bio, boolean verified,
                                        String verifiedType, boolean suspended,
                                        String accountCreatedAt, Long followers,
                                        Long following, Long subscribers, Long members,
                                        Long posts, Long views, String location, Instant now) {
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
                    "profile_url": "https://%s.example/profile/%s",
                    "self_declared_location": "%s",
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
                json(platform), json(platformUserId), json(location), verified, json(verifiedType),
                suspended, json(accountCreatedAt), numberOrNull(followers), numberOrNull(following),
                numberOrNull(subscribers), numberOrNull(members), numberOrNull(posts), numberOrNull(views));
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
                  "language": "en",
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

    private static String contentPressTvPost(String crawlTaskId, String authorId,
                                             String postId, String assetId, Instant now) {
        return socialContent(crawlTaskId, "x", "en", postId, authorId, "post",
                "Iranian naval commanders say patrol units in the Persian Gulf are monitoring US destroyer movements after a disputed cyber incident at a nuclear research site. Officials in Tehran deny any disruption to enrichment work and warn that new sanctions would be met with reciprocal measures. #Iran #PersianGulf #Nuclear",
                now.minus(3, ChronoUnit.HOURS), postId, null, null, null,
                new String[]{"Iran", "PersianGulf", "Nuclear"}, new String[]{},
                8_420L, 1_120L, 640L, 2_980L, 410L, 386_000L,
                new String[]{assetId});
    }

    private static String contentArabicRepost(String crawlTaskId, String authorId, String mentionId,
                                              String rootId, String postId, Instant now) {
        return socialContent(crawlTaskId, "x", "ar", postId, authorId, "repost",
                "التصعيد في الخليج لم يعد خبرا عابرا؛ الرواية الإيرانية تربط الهجوم السيبراني بالعقوبات الأميركية وتتهم إسرائيل بالتحريض.",
                now.minus(160, ChronoUnit.MINUTES), rootId, null, rootId, null,
                new String[]{"إيران", "الخليج_الفارسي", "أميركا"}, new String[]{mentionId},
                2_100L, 340L, 180L, 890L, null, 104_000L, null);
    }

    private static String contentPersianQuote(String crawlTaskId, String authorId, String mentionId,
                                              String quotedId, String postId, Instant now) {
        return socialContent(crawlTaskId, "x", "fa", postId, authorId, "quote",
                "وقتی تحریم‌ها هر روز شدیدتر می‌شود، مردم عادی هزینه تنش را می‌دهند. درباره حمله سایبری به تأسیسات هسته‌ای هنوز سند روشنی منتشر نشده اما هر طرف آن را به سود روایت خودش استفاده می‌کند. #ایران #تحریم",
                now.minus(145, ChronoUnit.MINUTES), quotedId, null, null, quotedId,
                new String[]{"ایران", "تحریم", "خلیج_فارس"}, new String[]{mentionId},
                930L, 220L, 70L, 310L, 95L, 61_000L, null);
    }

    private static String contentIrgcBot(String crawlTaskId, String authorId, String mentionId,
                                         String postId, Instant now) {
        return socialContent(crawlTaskId, "x", "fa", postId, authorId, "post",
                "گشت‌های دریایی ایران هر حرکت ناوهای آمریکا را زیر نظر دارند. حمله سایبری به سایت هسته‌ای شکست خورد و پاسخ مقاومت قطعی است. #ایران #سپاه #خلیج_فارس",
                now.minus(19, ChronoUnit.HOURS), postId, null, null, null,
                new String[]{"ایران", "سپاه", "خلیج_فارس"}, new String[]{mentionId},
                3L, 1L, 0L, 2L, null, 93_000L, null);
    }

    private static String contentVietnamQuote(String crawlTaskId, String authorId, String rootId,
                                              String quotedId, String postId, Instant now) {
        return socialContent(crawlTaskId, "x", "vi", postId, authorId, "quote",
                "Căng thẳng Mỹ-Iran tại Vịnh Ba Tư có thể đẩy giá dầu châu Á tăng nhanh. Việt Nam cần theo dõi rủi ro vận tải qua eo biển Hormuz và phản ứng của các hãng bảo hiểm hàng hải.",
                now.minus(110, ChronoUnit.MINUTES), rootId, null, null, quotedId,
                new String[]{"Iran", "Hormuz", "dầu_mỏ"}, new String[]{},
                1_240L, 180L, 96L, 420L, 40L, 78_000L, null);
    }

    private static String contentTagalogQuote(String crawlTaskId, String authorId, String rootId,
                                              String quotedId, String postId, Instant now) {
        return socialContent(crawlTaskId, "x", "tl", postId, authorId, "quote",
                "Kung magsara o maantala ang ruta sa Strait of Hormuz, tataas ang presyo ng langis at maaapektuhan ang remittance budgets ng maraming pamilyang Pilipino sa Gulf.",
                now.minus(98, ChronoUnit.MINUTES), rootId, null, null, quotedId,
                new String[]{"Iran", "Gulf", "OFW"}, new String[]{},
                870L, 140L, 61L, 260L, 28L, 49_000L, null);
    }

    private static String contentIsraelResponse(String crawlTaskId, String authorId, String mentionId,
                                                String postId, Instant now) {
        return socialContent(crawlTaskId, "x", "en", postId, authorId, "post",
                "Israel will continue to monitor threats from Iran's nuclear and missile programs. Claims blaming Israel for every cyber incident are part of Tehran's information campaign, not evidence. #Israel #NuclearDeal #Iran",
                now.minus(85, ChronoUnit.MINUTES), postId, null, null, null,
                new String[]{"Israel", "NuclearDeal", "Iran"}, new String[]{mentionId},
                6_900L, 1_020L, 210L, 1_480L, 360L, 244_000L, null);
    }

    private static String contentTelegramChannel(String crawlTaskId, String authorId, String postId, Instant now) {
        return socialContent(crawlTaskId, "telegram", "fa", postId, authorId, "channel_post",
                "گزارش داخلی: مسیرهای دریایی جنوب در آماده‌باش رسانه‌ای است. لینک تحلیل: https://t.me/sepah_field_updates/8821 #خلیج_فارس #مقاومت #جنگ_روایت‌ها",
                now.minus(70, ChronoUnit.MINUTES), postId, null, null, null,
                new String[]{"خلیج_فارس", "مقاومت", "جنگ_روایت‌ها"}, new String[]{},
                4_800L, 610L, 390L, 1_900L, null, 210_000L, null);
    }

    private static String contentChineseReport(String crawlTaskId, String authorId, String rootId,
                                               String postId, Instant now) {
        return socialContent(crawlTaskId, "x", "zh", postId, authorId, "post",
                "多家媒体称，美伊围绕波斯湾军事部署、核设施网络攻击归因和新一轮制裁展开舆论攻防。市场最关注霍尔木兹海峡航运是否受影响。#美伊冲突 #霍尔木兹海峡",
                now.minus(62, ChronoUnit.MINUTES), rootId, null, null, null,
                new String[]{"美伊冲突", "霍尔木兹海峡"}, new String[]{},
                1_760L, 330L, 120L, 510L, 58L, 88_000L, null);
    }

    private static String contentChineseReply(String crawlTaskId, String authorId, String rootId,
                                              String parentId, String postId, Instant now) {
        return socialContent(crawlTaskId, "x", "zh", postId, authorId, "reply",
                "现在最需要区分事实、归因和宣传口径。网络攻击是谁做的还没有可靠证据，但各方都在抢叙事主动权。",
                now.minus(45, ChronoUnit.MINUTES), rootId, parentId, null, null,
                new String[]{"信息战"}, new String[]{},
                260L, 42L, 18L, null, null, 12_000L, null);
    }

    private static String contentIndonesianQuote(String crawlTaskId, String authorId, String rootId,
                                                 String quotedId, String postId, Instant now) {
        return socialContent(crawlTaskId, "x", "id", postId, authorId, "quote",
                "Ketegangan AS-Iran di Teluk Persia bukan hanya isu militer. Dampaknya bisa terasa pada harga BBM, biaya logistik, dan sentimen pasar Asia Tenggara.",
                now.minus(38, ChronoUnit.MINUTES), rootId, null, null, quotedId,
                new String[]{"Iran", "Hormuz", "Energi"}, new String[]{},
                690L, 96L, 44L, 180L, 22L, 33_000L, null);
    }

    private static String contentBbcPersianPost(String crawlTaskId, String authorId, String postId, Instant now) {
        return socialContent(crawlTaskId, "youtube", "fa", postId, authorId, "post",
                "بی‌بی‌سی فارسی در برنامه امشب به ابهام‌های حمله سایبری منتسب به تأسیسات هسته‌ای ایران، نقش اسرائیل در روایت‌ها و واکنش بازار نفت می‌پردازد.",
                now.minus(30, ChronoUnit.MINUTES), postId, null, null, null,
                new String[]{"ایران", "بی‌بی‌سی", "هسته‌ای"}, new String[]{},
                3_200L, 540L, 180L, 730L, null, 126_000L, null);
    }

    private static String socialContent(String crawlTaskId, String platform, String language,
                                        String platformContentId, String authorUserId,
                                        String contentType, String bodyText, Instant publishedAt,
                                        String rootContentId, String parentContentId,
                                        String repostOfContentId, String quotedContentId,
                                        String[] hashtags, String[] mentions,
                                        Long likeCount, Long commentCount, Long shareCount,
                                        Long repostCount, Long quoteCount, Long viewCount,
                                        String[] mediaAssetIds) {
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
                    "language": "%s",
                    "published_at": "%s",
                    "url": "https://%s.example/status/%s",
                    "root_content_id": %s,
                    "parent_content_id": %s,
                    "repost_of_content_id": %s,
                    "quoted_content_id": %s,
                    "hashtags": %s,
                    "mentions": %s,
                    "external_urls": ["https://analysis.ft-test.example/us-iran-gulf-2026"],
                    "media_asset_ids": %s,
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
                json(contentType), json(bodyText), json(language), json(publishedAt.toString()),
                json(platform), json(platformContentId), jsonNullable(rootContentId),
                jsonNullable(parentContentId), jsonNullable(repostOfContentId), jsonNullable(quotedContentId),
                jsonArray(hashtags), jsonArray(mentions), jsonArray(mediaAssetIds),
                numberOrNull(likeCount), numberOrNull(commentCount),
                numberOrNull(shareCount), numberOrNull(repostCount), numberOrNull(quoteCount), numberOrNull(viewCount));
    }

    private static String newsBbc(String crawlTaskId, String newsId, Instant now) {
        String url = "https://www.bbc.com/news/world-middle-east-us-iran-gulf-2026-ft-test";
        return newsArticle(crawlTaskId, "en", newsId, url, "BBC News", "bbc.com", "Maya Richardson",
                "World",
                "US and Iran trade warnings after Gulf cyberattack claim",
                "US naval forces and Iranian patrol units have increased their public warnings after a disputed cyber incident linked to an Iranian nuclear research facility. Western diplomats say attribution remains unclear, while Israeli officials argue Tehran is using the episode to justify pressure on shipping lanes. Oil traders are watching insurance costs for tankers crossing the Strait of Hormuz, where even a limited disruption could affect Asian markets.",
                new String[]{"Iran", "US", "Middle East", "Nuclear"},
                now.minus(5, ChronoUnit.HOURS));
    }

    private static String newsPressTv(String crawlTaskId, String newsId, Instant now) {
        String url = "https://www.presstv.ir/Detail/2026/07/02/us-iran-persian-gulf-cyber-ft-test";
        return newsArticle(crawlTaskId, "fa", newsId, url, "Press TV", "presstv.ir", "تحریریه سیاسی",
                "West Asia",
                "ایران: حضور نظامی آمریکا عامل اصلی ناامنی در خلیج فارس است",
                "مقام‌های ایرانی می‌گویند حادثه سایبری اخیر علیه یک مرکز پژوهشی هسته‌ای بخشی از جنگ ترکیبی علیه کشور است و شواهد قطعی درباره عامل آن هنوز منتشر نشده است. رسانه‌های نزدیک به تهران تأکید می‌کنند که افزایش حضور ناوهای آمریکا در خلیج فارس، امنیت کشتیرانی را تهدید می‌کند. در این روایت، اسرائیل به تلاش برای منحرف کردن افکار عمومی از مذاکرات و تحریم‌ها متهم شده است.",
                new String[]{"ایران", "خلیج فارس", "تحریم", "هسته‌ای"},
                now.minus(4, ChronoUnit.HOURS));
    }

    private static String newsVnExpress(String crawlTaskId, String newsId, Instant now) {
        String url = "https://vnexpress.net/the-gioi/my-iran-vung-vinh-ba-tu-2026-ft-test";
        return newsArticle(crawlTaskId, "vi", newsId, url, "VnExpress", "vnexpress.net", "Nguyễn Minh Anh",
                "Thế giới",
                "Căng thẳng Mỹ-Iran làm gia tăng lo ngại về tuyến hàng hải Hormuz",
                "Các tín hiệu quân sự từ Mỹ và Iran tại Vịnh Ba Tư khiến thị trường năng lượng châu Á thận trọng hơn trong tuần này. Giới phân tích cho rằng vụ tấn công mạng nhằm vào cơ sở hạt nhân Iran vẫn còn nhiều tranh cãi về nguồn gốc, nhưng đã trở thành tâm điểm của cuộc chiến thông tin. Việt Nam và các nền kinh tế nhập khẩu năng lượng đang theo dõi sát chi phí vận tải và bảo hiểm qua eo biển Hormuz.",
                new String[]{"Iran", "Mỹ", "Hormuz", "năng lượng"},
                now.minus(3, ChronoUnit.HOURS));
    }

    private static String newsArticle(String crawlTaskId, String language, String contentId,
                                      String url, String sourceName, String domain,
                                      String author, String section, String title,
                                      String bodyText, String[] tags, Instant publishedAt) {
        return """
                {
                  "schema_version": "%s",
                  "record_type": "news_article",
                  "raw_record_id": "${raw_record_id}",
                  "raw_payload_hash": "${raw_payload_hash}",
                  "crawl_task_id": "%s",
                  "collected_at": "%s",
                  "platform": "news",
                  "language": "%s",
                  "source_url": "%s",
                  "data": {
                    "content_type": "article",
                    "platform_content_id": "%s",
                    "source_name": "%s",
                    "domain": "%s",
                    "author": "%s",
                    "section": "%s",
                    "title": "%s",
                    "body_text": "%s",
                    "language": "%s",
                    "tags": %s,
                    "published_at": "%s",
                    "url": "%s"
                  }
                }
                """.formatted(json(SCHEMA_VERSION), json(crawlTaskId), json(Instant.now().toString()),
                json(language), json(url), json(contentId), json(sourceName), json(domain), json(author),
                json(section), json(title), json(bodyText), json(language), jsonArray(tags),
                json(publishedAt.toString()), json(url));
    }

    private static String mediaAsset(String crawlTaskId, String platform, String language,
                                     String platformContentId, AssetPayload asset, Instant now) {
        return """
                {
                  "schema_version": "%s",
                  "record_type": "media_asset",
                  "raw_record_id": "${raw_record_id}",
                  "raw_payload_hash": "${raw_payload_hash}",
                  "crawl_task_id": "%s",
                  "collected_at": "%s",
                  "platform": "%s",
                  "language": "%s",
                  "data": {
                    "asset_id": "%s",
                    "platform_content_id": "%s",
                    "asset_type": "image",
                    "source_url": "%s",
                    "storage_uri": "%s",
                    "mime_type": "%s",
                    "sha256": "%s",
                    "file_size_bytes": %d,
                    "width": 1280,
                    "height": 720,
                    "thumbnail_uri": null,
                    "ocr_text": "Persian Gulf escalation briefing map",
                    "asr_text": null,
                    "minio_bucket": "%s",
                    "minio_key": "%s"
                  }
                }
                """.formatted(json(SCHEMA_VERSION), json(crawlTaskId), json(now.toString()),
                json(platform), json(language), json(asset.assetId()), json(platformContentId),
                json(asset.sourceUrl()), json(asset.storageUri()), json(asset.mimeType()), json(asset.sha256()),
                asset.fileSizeBytes(), json(MINIO_BUCKET), json(asset.minioKey()));
    }

    private static AssetPayload prepareAsset(String assetId, String key, String mimeType,
                                             Path imagePath, boolean allowPlaceholderSize) throws Exception {
        if (imagePath == null) {
            String fakeHash = UUID.randomUUID().toString().replace("-", "");
            long fileSize = allowPlaceholderSize ? 142_336L : 238_592L;
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

    private static void printSummary(String crawlTaskId, String postId1, String postId2,
                                     String postId3, String postId5, String postId6,
                                     String assetId1) {
        System.out.println("========= 美伊冲突场景测试数据集 发送完成 =========");
        System.out.println("crawlTaskId = " + crawlTaskId);
        System.out.println();
        System.out.println("语种覆盖: 英语(en) 波斯语(fa) 阿拉伯语(ar) 中文(zh) 越南语(vi) 塔加洛语(tl) 印尼语(id)");
        System.out.println("平台覆盖: x / telegram / youtube / news");
        System.out.println();
        System.out.println("传播链：");
        System.out.println("  postId1 (伊朗官媒英文原帖) = " + postId1);
        System.out.println("  └─ postId2 (阿拉伯语转发) = " + postId2);
        System.out.println("  └─ postId3 (波斯语引用评论) = " + postId3);
        System.out.println("     └─ postId5 (越南语二次传播) = " + postId5);
        System.out.println("     └─ postId6 (塔加洛语二次传播) = " + postId6);
        System.out.println();
        System.out.println("图片资产：assetId1 已关联到 postId1，assetId1 = " + assetId1);
        System.out.println("可在 Neo4j 里查询：");
        System.out.println("  MATCH (c:MediaContent)-[:PUBLISHED_BY]->(a:SocialAccount) RETURN c, a LIMIT 5");
        System.out.println();
        System.out.println("全局验证SQL:");
        System.out.println("  SELECT language, count(*) FROM raw_records");
        System.out.println("    WHERE crawl_task_id = '" + crawlTaskId + "'");
        System.out.println("    GROUP BY language ORDER BY count(*) DESC;");
        System.out.println();
        System.out.println("  SELECT content_type, platform, count(*) FROM media_contents");
        System.out.println("    GROUP BY content_type, platform ORDER BY platform;");
        System.out.println("==========================================");
    }

    private static void printSelfCheck() {
        System.out.println();
        System.out.println("=== 自检 ===");
        System.out.println("body_text 语种: en/fa/ar/zh/vi/tl/id 均使用对应语言正文，未用英文占位替代。");
        System.out.println("传播链字段: postId2.repost_of_content_id=postId1; postId3.quoted_content_id=postId1; postId5/postId6/postId11.quoted_content_id=postId3; postId10.parent_content_id=postId9。");
        System.out.println("account_relation: source_platform_user_id/target_platform_user_id 均来自本工具发送的 social_account。");
        System.out.println("isValidSchema: collection_task 有 root.crawl_task_id; social_account 有 root.platform/data.platform_user_id; account_relation 有 root.platform/source/target/relation_type/observed_at; social_content 有 root.platform/data.platform_content_id/data.author_platform_user_id; news_article 有 root.platform/root.source_url; media_asset 有 data.sha256/data.asset_type。");
        System.out.println("Normalizer 字段对齐: collection_task/social_account/account_relation/social_content/news_article/media_asset 均使用代码实际读取的字段名。");
    }

    private record AssetPayload(String assetId, String mimeType, String sha256,
                                long fileSizeBytes, String storageUri,
                                String sourceUrl, String minioKey) {
    }
}
