package com.idata.profile.mapper.task;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.idata.profile.entity.task.IdentificationResult;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.UUID;

@Mapper
public interface IdentificationResultMapper extends BaseMapper<IdentificationResult> {

    @Delete("DELETE FROM identification_results WHERE task_id = #{taskId}")
    int deleteByTaskId(@Param("taskId") UUID taskId);
}
