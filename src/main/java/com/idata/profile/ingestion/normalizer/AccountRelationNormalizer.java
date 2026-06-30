package com.idata.profile.ingestion.normalizer;

import com.idata.profile.entity.account.AccountRelation;
import com.idata.profile.entity.raw.RawRecord;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * account_relation 的Step3标准化映射逻辑。
 * 重要：fromAccountId/toAccountId故意留空，由独立批处理回填，
 * 见 docs/课题四_数据处理流程_v2.md 第四章 Step4回填机制详解。
 */
@Component
public class AccountRelationNormalizer {

    public AccountRelation normalize(Object kafkaMessage, RawRecord rawRecord) {
        AccountRelation relation = new AccountRelation();
        relation.setId(UUID.randomUUID());
        relation.setRawRecordId(rawRecord.getId());
        relation.setFromAccountId(null);  // 故意留空
        relation.setToAccountId(null);    // 故意留空
        relation.setSyncedToNeo4j(false);
        relation.setConfidence(BigDecimal.ONE);
        // TODO: 从kafkaMessage.data提取：sourcePlatformUserId, targetPlatformUserId,
        //   platform, relationType, observedAt, occurredAt, source

        return relation;
    }
}
