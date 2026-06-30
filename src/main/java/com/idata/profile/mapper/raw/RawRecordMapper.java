package com.idata.profile.mapper.raw;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.idata.profile.entity.raw.RawRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

@Mapper
public interface RawRecordMapper extends BaseMapper<RawRecord> {

    /** Step1去重检查：按sourceRecordId判断是否已存在 */
    @Select("SELECT EXISTS(SELECT 1 FROM raw_records WHERE source_record_id = #{sourceRecordId})")
    boolean existsBySourceRecordId(@Param("sourceRecordId") String sourceRecordId);

    /** Step1去重检查：按payloadHash判断内容是否重复 */
    @Select("SELECT EXISTS(SELECT 1 FROM raw_records WHERE payload_hash = #{payloadHash})")
    boolean existsByPayloadHash(@Param("payloadHash") String payloadHash);

    /**
     * 单行追溯全部处理过程，见 docs/课题四_数据处理流程_v2.md 第10.1节。
     * 用法示例：mapper.selectFullTraceBySourceRecordId("x_post_123456")
     */
    @Select("SELECT id, source_record_id, pipeline_status, t1_output, t2_output, t3_output, t4_output " +
            "FROM raw_records WHERE source_record_id = #{sourceRecordId}")
    RawRecord selectFullTraceBySourceRecordId(@Param("sourceRecordId") String sourceRecordId);
}
