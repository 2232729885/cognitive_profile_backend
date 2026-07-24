package com.idata.profile.mapper.raw;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.idata.profile.entity.raw.RawRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

@Mapper
public interface RawRecordMapper extends BaseMapper<RawRecord> {

    /** Step1去重检查：按sourceRecordId判断是否已存在 */
    @Select("SELECT EXISTS(SELECT 1 FROM raw_records WHERE source_record_id = #{sourceRecordId})")
    boolean existsBySourceRecordId(@Param("sourceRecordId") String sourceRecordId);

    @Select("SELECT * FROM raw_records WHERE source_record_id = #{sourceRecordId}")
    RawRecord selectBySourceRecordId(@Param("sourceRecordId") String sourceRecordId);

    /** Step1去重检查：按payloadHash判断内容是否重复 */
    @Select("SELECT EXISTS(SELECT 1 FROM raw_records WHERE payload_hash = #{payloadHash})")
    boolean existsByPayloadHash(@Param("payloadHash") String payloadHash);

    /**
     * 单行追溯全部处理过程，见 docs/数据处理流程.md 第10.1节。
     * 用法示例：mapper.selectFullTraceBySourceRecordId("x_post_123456")
     */
    @Select("SELECT id, source_record_id, pipeline_status, t1_output, t2_output, t3_output, t4_output " +
            "FROM raw_records WHERE source_record_id = #{sourceRecordId}")
    RawRecord selectFullTraceBySourceRecordId(@Param("sourceRecordId") String sourceRecordId);

    @Select("""
            SELECT rr.* FROM raw_records rr
            JOIN pipeline_tasks pt ON pt.id = rr.pipeline_task_id
            WHERE rr.record_type IN ('social_content', 'news_article')
              AND rr.pipeline_status NOT IN ('T4_INDEXED', 'FAILED')
              AND rr.pipeline_task_id IS NOT NULL
              AND COALESCE(pt.status, 'PENDING') <> 'DONE'
              AND (
                (
                  COALESCE(pt.status, 'PENDING') <> 'RUNNING'
                  AND
                  COALESCE(pt.t1_status, 'pending') <> 'running'
                  AND COALESCE(pt.t2_status, 'pending') <> 'running'
                  AND COALESCE(pt.t3_status, 'pending') <> 'running'
                  AND COALESCE(pt.t4_status, 'pending') <> 'running'
                  AND rr.updated_at < NOW() - (#{stuckMinutes} || ' minutes')::INTERVAL
                )
                OR (
                  (
                    COALESCE(pt.status, 'PENDING') = 'RUNNING'
                    OR COALESCE(pt.t1_status, 'pending') = 'running'
                    OR COALESCE(pt.t2_status, 'pending') = 'running'
                    OR COALESCE(pt.t3_status, 'pending') = 'running'
                    OR COALESCE(pt.t4_status, 'pending') = 'running'
                  )
                  AND pt.updated_at < NOW() - (#{runningStuckMinutes} || ' minutes')::INTERVAL
                )
              )
            ORDER BY rr.updated_at ASC
            LIMIT #{limit}
            """)
    List<RawRecord> selectStuckPipelineRecords(@Param("stuckMinutes") int stuckMinutes,
                                               @Param("runningStuckMinutes") int runningStuckMinutes,
                                               @Param("limit") int limit);
}
