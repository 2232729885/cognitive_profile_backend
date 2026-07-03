package com.idata.profile.mapper.dedup;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.idata.profile.entity.dedup.EntityFusionRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Mapper
public interface EntityFusionRecordMapper extends BaseMapper<EntityFusionRecord> {

    @Select("SELECT * FROM entity_fusion_records WHERE job_run_id = #{jobRunId} ORDER BY created_at")
    List<EntityFusionRecord> selectByJobRunId(@Param("jobRunId") UUID jobRunId);

    @Select("SELECT MAX(created_at) FROM entity_fusion_records")
    OffsetDateTime selectLastCreatedAt();
}
