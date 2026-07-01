package com.idata.profile.mapper.content;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.idata.profile.entity.content.CollectionTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CollectionTaskMapper extends BaseMapper<CollectionTask> {

    @Insert("""
            INSERT INTO collection_tasks (
                id, raw_record_id, crawl_task_id, collection_method, seed_type,
                seed_value, query_expression, platform, time_window_start, time_window_end,
                target_languages, target_regions, collector_version, records_collected, raw_payload
            )
            VALUES (
                #{task.id}, #{task.rawRecordId}, #{task.crawlTaskId}, #{task.collectionMethod}, #{task.seedType},
                #{task.seedValue}, #{task.queryExpression}, #{task.platform}, #{task.timeWindowStart}, #{task.timeWindowEnd},
                #{task.targetLanguages,typeHandler=com.idata.profile.infra.mybatis.StringArrayTypeHandler},
                #{task.targetRegions,typeHandler=com.idata.profile.infra.mybatis.StringArrayTypeHandler},
                #{task.collectorVersion}, #{task.recordsCollected},
                #{task.rawPayload,typeHandler=com.idata.profile.infra.mybatis.JsonbStringTypeHandler}
            )
            ON CONFLICT (crawl_task_id) DO UPDATE SET
                raw_record_id = EXCLUDED.raw_record_id,
                collection_method = EXCLUDED.collection_method,
                seed_type = EXCLUDED.seed_type,
                seed_value = EXCLUDED.seed_value,
                query_expression = EXCLUDED.query_expression,
                platform = EXCLUDED.platform,
                time_window_start = EXCLUDED.time_window_start,
                time_window_end = EXCLUDED.time_window_end,
                target_languages = EXCLUDED.target_languages,
                target_regions = EXCLUDED.target_regions,
                collector_version = EXCLUDED.collector_version,
                records_collected = EXCLUDED.records_collected,
                raw_payload = EXCLUDED.raw_payload
            """)
    int upsertByCrawlTaskId(@Param("task") CollectionTask task);
}
