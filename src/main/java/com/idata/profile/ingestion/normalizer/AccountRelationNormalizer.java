package com.idata.profile.ingestion.normalizer;

import com.idata.profile.entity.account.AccountRelation;
import com.idata.profile.entity.raw.RawRecord;
import com.idata.profile.ingestion.consumer.IngestionMessageSupport;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.UUID;

@Component
public class AccountRelationNormalizer {

    public AccountRelation normalize(Object kafkaMessage, RawRecord rawRecord) {
        JsonNode root = IngestionMessageSupport.root(kafkaMessage);
        JsonNode data = IngestionMessageSupport.data(kafkaMessage);

        AccountRelation relation = new AccountRelation();
        relation.setId(UUID.randomUUID());
        relation.setRawRecordId(rawRecord.getId());
        relation.setSourcePlatformUserId(IngestionMessageSupport.text(data, "source_platform_user_id"));
        relation.setTargetPlatformUserId(IngestionMessageSupport.text(data, "target_platform_user_id"));
        relation.setPlatform(IngestionMessageSupport.text(root, "platform"));
        relation.setRelationType(IngestionMessageSupport.text(data, "relation_type"));
        relation.setObservedAt(IngestionMessageSupport.parseOffsetDateTime(data.path("observed_at")));
        relation.setOccurredAt(IngestionMessageSupport.parseOffsetDateTime(data.path("occurred_at")));
        relation.setSource(IngestionMessageSupport.text(data, "source"));
        relation.setFromAccountId(null);
        relation.setToAccountId(null);
        relation.setSyncedToNeo4j(false);
        relation.setConfidence(BigDecimal.ONE);

        return relation;
    }
}
