package com.idata.profile.dedup;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.idata.profile.batch.dedup.EntityDeduplicationJob;
import com.idata.profile.entity.dedup.EntityFusionRecord;
import com.idata.profile.mapper.dedup.EntityFusionRecordMapper;
import com.idata.profile.mapper.graph.EventMapper;
import com.idata.profile.mapper.graph.NarrativeMapper;
import com.idata.profile.mapper.graph.OrganizationMapper;
import com.idata.profile.mapper.graph.PersonMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class FusionService {

    private static final Set<String> ENTITY_TYPES = Set.of("person", "organization", "event", "narrative");

    private final EntityFusionRecordMapper entityFusionRecordMapper;
    private final PersonMapper personMapper;
    private final OrganizationMapper organizationMapper;
    private final EventMapper eventMapper;
    private final NarrativeMapper narrativeMapper;
    private final EntityDeduplicationJob entityDeduplicationJob;

    public IPage<EntityFusionRecord> listFusionRecords(String entityType, int page, int size) {
        LambdaQueryWrapper<EntityFusionRecord> wrapper = new LambdaQueryWrapper<EntityFusionRecord>()
                .eq(hasText(entityType), EntityFusionRecord::getEntityType, entityType)
                .orderByDesc(EntityFusionRecord::getCreatedAt);
        return entityFusionRecordMapper.selectPage(
                new Page<>(Math.max(page, 0) + 1L, normalizeSize(size)), wrapper);
    }

    public IPage<EntityFusionRecord> listPendingReview(String entityType, int page, int size) {
        LambdaQueryWrapper<EntityFusionRecord> wrapper = new LambdaQueryWrapper<EntityFusionRecord>()
                .eq(EntityFusionRecord::getFusionMethod, "t3_pending_review")
                .eq(hasText(entityType), EntityFusionRecord::getEntityType, entityType)
                .orderByDesc(EntityFusionRecord::getCreatedAt);
        return entityFusionRecordMapper.selectPage(
                new Page<>(Math.max(page, 0) + 1L, normalizeSize(size)), wrapper);
    }

    public void reviewFusionRecord(UUID recordId, String action) {
        EntityFusionRecord record = entityFusionRecordMapper.selectById(recordId);
        if (record == null) {
            throw new RuntimeException("记录不存在: " + recordId);
        }

        if ("approve".equals(action)) {
            record.setFusionMethod("t3_human_approved");
            entityFusionRecordMapper.updateById(record);
            log.info("[DedupController] human approved merge, recordId={}", recordId);
        } else {
            record.setFusionMethod("t3_human_rejected");
            entityFusionRecordMapper.updateById(record);
            log.info("[DedupController] human rejected merge, recordId={}", recordId);
        }
    }

    public List<EntityFusionRecord> listByJobRunId(UUID jobRunId) {
        return entityFusionRecordMapper.selectByJobRunId(jobRunId);
    }

    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("person", statusMap(personMapper.selectDedupStatusStats()));
        result.put("organization", statusMap(organizationMapper.selectDedupStatusStats()));
        result.put("event", statusMap(eventMapper.selectDedupStatusStats()));
        result.put("narrative", statusMap(narrativeMapper.selectDedupStatusStats()));
        OffsetDateTime lastJobRunAt = entityFusionRecordMapper.selectLastCreatedAt();
        result.put("lastJobRunAt", lastJobRunAt == null ? null : lastJobRunAt.toString());
        return result;
    }

    public UUID trigger() {
        UUID jobRunId = UUID.randomUUID();
        return entityDeduplicationJob.tryStartAsync(jobRunId) ? jobRunId : null;
    }

    public boolean isValidEntityType(String entityType) {
        return !hasText(entityType) || ENTITY_TYPES.contains(entityType);
    }

    public boolean isRunning() {
        return entityDeduplicationJob.isRunning();
    }

    private Map<String, Object> statusMap(List<Map<String, Object>> rows) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pending", 0L);
        result.put("canonical", 0L);
        result.put("deduplicated", 0L);
        for (Map<String, Object> row : rows) {
            String status = stringValue(value(row, "status"));
            if (hasText(status)) {
                result.put(status, numberValue(value(row, "cnt")));
            }
        }
        return result;
    }

    private Object value(Map<String, Object> row, String key) {
        if (row.containsKey(key)) {
            return row.get(key);
        }
        return row.get(key.toUpperCase());
    }

    private int normalizeSize(int size) {
        if (size <= 0) {
            return 20;
        }
        return Math.min(size, 100);
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private long numberValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
