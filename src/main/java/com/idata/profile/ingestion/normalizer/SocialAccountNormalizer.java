package com.idata.profile.ingestion.normalizer;

import com.idata.profile.entity.account.SocialAccount;
import com.idata.profile.entity.account.SocialAccountSnapshot;
import com.idata.profile.entity.raw.RawRecord;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * social_account 的Step3标准化映射逻辑。
 * 输出主表UPSERT数据 + 快照追加数据，见 docs/课题四_数据处理流程_v2.md 第三章。
 */
@Component
public class SocialAccountNormalizer {

    public NormalizedAccount normalize(Object kafkaMessage, RawRecord rawRecord) {
        SocialAccount account = new SocialAccount();
        // TODO: 从kafkaMessage.data提取：platform, platformUserId, accountEntityType,
        //   platformNativeType, handle, displayName, bio, verified, verifiedType,
        //   各项count字段，见字段速查表

        SocialAccountSnapshot snapshot = new SocialAccountSnapshot();
        snapshot.setId(UUID.randomUUID());
        snapshot.setRawRecordId(rawRecord.getId());
        snapshot.setSnapshotAt(rawRecord.getCollectedAt());
        // TODO: 从account复制当前快照字段

        return new NormalizedAccount(account, snapshot);
    }

    @Data
    public static class NormalizedAccount {
        private final SocialAccount account;
        private final SocialAccountSnapshot snapshot;
    }
}
