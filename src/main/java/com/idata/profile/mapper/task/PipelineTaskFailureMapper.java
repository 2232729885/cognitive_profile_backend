package com.idata.profile.mapper.task;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.idata.profile.entity.task.PipelineTaskFailure;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

@Mapper
public interface PipelineTaskFailureMapper extends BaseMapper<PipelineTaskFailure> {

    @Select("SELECT * FROM pipeline_task_failures WHERE task_id = #{taskId} ORDER BY created_at DESC")
    List<PipelineTaskFailure> selectByTaskId(@Param("taskId") UUID taskId);

    @Select("SELECT * FROM pipeline_task_failures ORDER BY created_at DESC LIMIT #{limit}")
    List<PipelineTaskFailure> selectRecent(@Param("limit") int limit);
}
