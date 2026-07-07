package com.idata.profile.infra.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaMonitorService {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:cognitive-profile-ingestion}")
    private String consumerGroupId;

    private static final List<String> MONITORED_TOPICS = List.of(
            KafkaTopicConstants.SOCIAL_CONTENT,
            KafkaTopicConstants.SOCIAL_ACCOUNT,
            KafkaTopicConstants.ACCOUNT_RELATION,
            KafkaTopicConstants.MEDIA_ASSET,
            KafkaTopicConstants.NEWS_ARTICLE,
            KafkaTopicConstants.COLLECTION_TASK,
            KafkaTopicConstants.PIPELINE_PENDING
    );

    public List<Map<String, Object>> getTopicStats() {
        try (AdminClient adminClient = AdminClient.create(adminConfig())) {
            Map<TopicPartition, Long> endOffsets = getEndOffsets(adminClient);
            Map<TopicPartition, Long> committedOffsets = getCommittedOffsets(adminClient);

            Map<String, long[]> topicStats = new LinkedHashMap<>();
            for (String topic : MONITORED_TOPICS) {
                topicStats.put(topic, new long[]{0L, 0L});
            }

            for (Map.Entry<TopicPartition, Long> entry : endOffsets.entrySet()) {
                String topic = entry.getKey().topic();
                if (!topicStats.containsKey(topic)) {
                    continue;
                }

                long end = entry.getValue() == null ? 0L : entry.getValue();
                long committed = committedOffsets.getOrDefault(entry.getKey(), 0L);
                long lag = Math.max(0, end - committed);

                topicStats.get(topic)[0] += end;
                topicStats.get(topic)[1] += lag;
            }

            List<Map<String, Object>> result = new ArrayList<>();
            for (Map.Entry<String, long[]> entry : topicStats.entrySet()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("topic", entry.getKey());
                item.put("totalMessages", entry.getValue()[0]);
                item.put("lag", entry.getValue()[1]);
                item.put("consumed", Math.max(0, entry.getValue()[0] - entry.getValue()[1]));
                item.put("consumerGroup", consumerGroupId);
                result.add(item);
            }
            return result;
        } catch (Exception e) {
            log.warn("获取Kafka Topic统计失败", e);
            return MONITORED_TOPICS.stream().map(topic -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("topic", topic);
                item.put("totalMessages", -1L);
                item.put("lag", -1L);
                item.put("consumed", -1L);
                item.put("consumerGroup", consumerGroupId);
                item.put("error", "无法连接Kafka");
                return item;
            }).collect(Collectors.toList());
        }
    }

    private Map<String, Object> adminConfig() {
        return Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    }

    private Map<TopicPartition, Long> getEndOffsets(AdminClient adminClient) throws Exception {
        List<TopicPartition> partitions = new ArrayList<>();
        Map<String, TopicDescription> descriptions = adminClient
                .describeTopics(MONITORED_TOPICS)
                .allTopicNames()
                .get(10, TimeUnit.SECONDS);

        for (Map.Entry<String, TopicDescription> entry : descriptions.entrySet()) {
            for (TopicPartitionInfo partitionInfo : entry.getValue().partitions()) {
                partitions.add(new TopicPartition(entry.getKey(), partitionInfo.partition()));
            }
        }
        if (partitions.isEmpty()) {
            return Map.of();
        }

        return adminClient.listOffsets(
                        partitions.stream().collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest())))
                .all()
                .get(10, TimeUnit.SECONDS)
                .entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().offset()));
    }

    private Map<TopicPartition, Long> getCommittedOffsets(AdminClient adminClient) {
        try {
            return adminClient.listConsumerGroupOffsets(consumerGroupId)
                    .partitionsToOffsetAndMetadata()
                    .get(10, TimeUnit.SECONDS)
                    .entrySet()
                    .stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> e.getValue() == null ? 0L : e.getValue().offset()));
        } catch (Exception e) {
            log.warn("获取Consumer Group offset失败, group={}", consumerGroupId, e);
            return Map.of();
        }
    }
}
