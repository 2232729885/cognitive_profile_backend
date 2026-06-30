package com.idata.profile.ingestion.dedup;

import com.idata.profile.mapper.raw.RawRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Step1去重检查，所有六种record_type共用。
 * 见 docs/课题四_数据处理流程_v2.md 各类型流程的Step1。
 */
@Component
@RequiredArgsConstructor
public class DeduplicationChecker {

    private final RawRecordMapper rawRecordMapper;

    /**
     * @return true表示重复（sourceRecordId已存在 或 payloadHash已存在），应直接ack跳过
     */
    public boolean isDuplicate(String sourceRecordId, String payloadHash) {
        return rawRecordMapper.existsBySourceRecordId(sourceRecordId)
                || rawRecordMapper.existsByPayloadHash(payloadHash);
    }
}
