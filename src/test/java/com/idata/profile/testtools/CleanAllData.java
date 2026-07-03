package com.idata.profile.testtools;

import io.milvus.exception.MilvusException;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpStatus;
import org.apache.http.message.BasicHeader;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.AlterConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

public class CleanAllData {

    private static final List<String> PG_TABLES = List.of(
            "entity_fusion_records",
            "identification_results",
            "identification_tasks",
            "session_messages",
            "sessions",
            "workflow_tasks",
            "pipeline_tasks",
            "person_profiles",
            "narratives",
            "events",
            "organizations",
            "persons",
            "media_assets",
            "account_relations",
            "social_account_snapshots",
            "social_accounts",
            "collection_tasks",
            "media_contents",
            "raw_records",
            "batch_import_tasks"
    );

    private static final List<String> MILVUS_COLLECTIONS = List.of(
            "text_embeddings",
            "image_embeddings",
            "entity_embeddings"
    );

    private static final List<String> ES_INDICES = List.of(
            "media_contents_index",
            "entities_index",
            "workflow_logs_index"
    );

    private static final List<String> KAFKA_TOPICS = List.of(
            "kt3.social_content",
            "kt3.social_account",
            "kt3.account_relation",
            "kt3.media_asset",
            "kt3.news_article",
            "kt3.collection_task"
    );

    public static void main(String[] args) {
        System.out.println("注意：请先停止主项目（cognitive-profile-backend），再运行此清理工具");

        Map<String, Boolean> results = new LinkedHashMap<>();
        results.put("PostgreSQL", runSafely("PostgreSQL", CleanAllData::cleanPostgreSQL));
        results.put("Neo4j", runSafely("Neo4j", CleanAllData::cleanNeo4j));
        results.put("Milvus", runSafely("Milvus", CleanAllData::cleanMilvus));
        results.put("Elasticsearch", runSafely("Elasticsearch", CleanAllData::cleanElasticsearch));
        results.put("Kafka", runSafely("Kafka", CleanAllData::resetKafkaOffsets));
        results.put("MinIO", runSafely("MinIO", CleanAllData::cleanMinio));

        System.out.println();
        System.out.println("========= 清理结果汇总 =========");
        results.forEach((name, success) -> System.out.println(name + ": " + (success ? "SUCCESS" : "FAILED")));
        System.out.println("================================");
        System.out.println("========= 所有数据已清理完毕，可以开始新一轮测试 =========");
    }

    private static boolean runSafely(String name, ThrowingRunnable runnable) {
        try {
            runnable.run();
            return true;
        } catch (Exception e) {
            System.err.println(name + " 清理失败: " + e.getMessage());
            e.printStackTrace(System.err);
            return false;
        }
    }

    private static void cleanPostgreSQL() throws Exception {
        System.out.println("[1/6] PostgreSQL 开始清理...");
        int cleaned = 0;
        try (Connection connection = DriverManager.getConnection(
                "jdbc:postgresql://172.16.40.232:5432/postgres", "postgres", "postgres");
             Statement statement = connection.createStatement()) {
            for (String table : PG_TABLES) {
                statement.execute("TRUNCATE TABLE " + table + " CASCADE");
                cleaned++;
            }
        }
        System.out.println("[1/6] PostgreSQL 清理完成，共清理 " + cleaned + " 张表");
    }

    private static void cleanNeo4j() {
        System.out.println("[2/6] Neo4j 开始清理...");
        long nodeCountBefore;
        long nodeCountAfter;
        try (Driver driver = GraphDatabase.driver(
                "neo4j://172.16.40.232:7688",
                AuthTokens.basic("neo4j", "neo4jpasswd"));
             Session session = driver.session()) {
            nodeCountBefore = session.run("MATCH (n) RETURN count(n) AS nodeCount")
                    .single()
                    .get("nodeCount")
                    .asLong();
            session.run("MATCH (n) DETACH DELETE n").consume();
            nodeCountAfter = session.run("MATCH (n) RETURN count(n) AS nodeCount")
                    .single()
                    .get("nodeCount")
                    .asLong();
        }
        System.out.println("[2/6] Neo4j 清理完成，删除节点数: " + nodeCountBefore + ", 当前节点数: " + nodeCountAfter);
    }

    private static void cleanMilvus() {
        System.out.println("[3/6] Milvus 开始清理...");
        MilvusClientV2 milvusClient = new MilvusClientV2(
                ConnectConfig.builder().uri("http://172.16.40.232:19530").build());
        try {
            for (String collection : MILVUS_COLLECTIONS) {
                try {
                    Boolean exists = milvusClient.hasCollection(HasCollectionReq.builder()
                            .collectionName(collection)
                            .build());
                    if (!Boolean.TRUE.equals(exists)) {
                        System.out.println("[3/6] Milvus collection 不存在，跳过: " + collection);
                        continue;
                    }
                    milvusClient.delete(DeleteReq.builder()
                            .collectionName(collection)
                            .filter("id != ''")
                            .build());
                    System.out.println("[3/6] Milvus 已清理 collection: " + collection);
                } catch (MilvusException e) {
                    System.err.println("[3/6] Milvus collection 清理失败，继续下一个: "
                            + collection + ", error=" + e.getMessage());
                }
            }
        } finally {
            milvusClient.close();
        }
        System.out.println("[3/6] Milvus 清理完成");
    }

    private static void cleanElasticsearch() throws Exception {
        System.out.println("[4/6] Elasticsearch 开始清理...");
        String auth = Base64.getEncoder().encodeToString("elastic:elastic".getBytes(StandardCharsets.UTF_8));
        try (RestClient restClient = RestClient.builder(new HttpHost("172.16.40.232", 9200, "http"))
                .setDefaultHeaders(new Header[]{new BasicHeader("Authorization", "Basic " + auth)})
                .build()) {
            for (String index : ES_INDICES) {
                if (!indexExists(restClient, index)) {
                    System.out.println("[4/6] Elasticsearch index 不存在，跳过: " + index);
                    continue;
                }
                Request request = new Request("POST", "/" + index + "/_delete_by_query");
                request.setJsonEntity("{\"query\":{\"match_all\":{}}}");
                restClient.performRequest(request);
                System.out.println("[4/6] Elasticsearch 已清理 index: " + index);
            }
        }
        System.out.println("[4/6] Elasticsearch 清理完成");
    }

    private static boolean indexExists(RestClient restClient, String index) throws Exception {
        try {
            Response response = restClient.performRequest(new Request("HEAD", "/" + index));
            return response.getStatusLine().getStatusCode() == HttpStatus.SC_OK;
        } catch (ResponseException e) {
            if (e.getResponse().getStatusLine().getStatusCode() == HttpStatus.SC_NOT_FOUND) {
                return false;
            }
            throw e;
        }
    }

    private static void resetKafkaOffsets() throws Exception {
        System.out.println("[5/6] Kafka 开始重置 offset...");
        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "172.16.40.232:9092");
        try (AdminClient adminClient = AdminClient.create(adminProps)) {
            Map<TopicPartition, OffsetSpec> latestRequests = new LinkedHashMap<>();
            for (String topic : KAFKA_TOPICS) {
                adminClient.describeTopics(List.of(topic))
                        .allTopicNames()
                        .get(30, TimeUnit.SECONDS)
                        .get(topic)
                        .partitions()
                        .forEach(partition -> latestRequests.put(
                                new TopicPartition(topic, partition.partition()),
                                OffsetSpec.latest()));
            }

            ListOffsetsResult offsetsResult = adminClient.listOffsets(latestRequests);
            Map<TopicPartition, OffsetAndMetadata> offsets = new LinkedHashMap<>();
            for (TopicPartition topicPartition : latestRequests.keySet()) {
                long latestOffset = offsetsResult.partitionResult(topicPartition)
                        .get(30, TimeUnit.SECONDS)
                        .offset();
                offsets.put(topicPartition, new OffsetAndMetadata(latestOffset));
            }

            AlterConsumerGroupOffsetsResult result = adminClient.alterConsumerGroupOffsets(
                    "cognitive-profile-ingestion", offsets);
            result.all().get(30, TimeUnit.SECONDS);
        }
        System.out.println("[5/6] Kafka offset 重置完成");
    }

    private static void cleanMinio() throws Exception {
        System.out.println("[6/6] MinIO 开始清理 test/ 前缀对象...");
        int deleted = 0;
        MinioClient minioClient = MinioClient.builder()
                .endpoint("http://172.16.40.232:9000")
                .credentials("minioadmin", "minioadmin")
                .build();

        if (!minioClient.bucketExists(io.minio.BucketExistsArgs.builder()
                .bucket("media-assets")
                .build())) {
            System.out.println("[6/6] MinIO bucket 不存在，跳过: media-assets");
            System.out.println("[6/6] MinIO 清理完成，删除 0 个对象");
            return;
        }

        Iterable<Result<Item>> objects = minioClient.listObjects(ListObjectsArgs.builder()
                .bucket("media-assets")
                .prefix("test/")
                .recursive(true)
                .build());
        List<String> objectNames = new ArrayList<>();
        for (Result<Item> result : objects) {
            objectNames.add(result.get().objectName());
        }
        for (String objectName : objectNames) {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket("media-assets")
                    .object(objectName)
                    .build());
            deleted++;
        }
        System.out.println("[6/6] MinIO 清理完成，删除 " + deleted + " 个对象");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
