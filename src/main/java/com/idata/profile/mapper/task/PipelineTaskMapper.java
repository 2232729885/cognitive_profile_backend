package com.idata.profile.mapper.task;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.idata.profile.entity.task.PipelineTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

@Mapper
public interface PipelineTaskMapper extends BaseMapper<PipelineTask> {

    @Select("SELECT * FROM pipeline_tasks WHERE status = 'FAILED' ORDER BY created_at DESC")
    List<PipelineTask> selectFailedTasks();

    /** 性能监控查询，见 docs/数据处理流程.md 第10.3节 */
    @Select("SELECT AVG(t1_duration_ms) AS avgT1Ms, AVG(t2_duration_ms) AS avgT2Ms, " +
            "AVG(t3_duration_ms) AS avgT3Ms, AVG(t4_duration_ms) AS avgT4Ms, " +
            "COUNT(*) FILTER (WHERE status = 'DONE') AS doneCount, " +
            "COUNT(*) FILTER (WHERE status = 'FAILED') AS failedCount " +
            "FROM pipeline_tasks WHERE created_at > NOW() - (#{hours} || ' hours')::INTERVAL")
    java.util.Map<String, Object> selectPerformanceStats(@Param("hours") int hours);

    @Update("""
            UPDATE pipeline_tasks
            SET status = 'QUEUED',
                queued_at = NOW(),
                updated_at = NOW()
            WHERE id = #{taskId}
              AND COALESCE(status, 'PENDING') <> 'DONE'
              AND (
                COALESCE(status, 'PENDING') IN ('PENDING', 'FAILED')
                OR (
                  COALESCE(status, 'PENDING') = 'QUEUED'
                  AND updated_at < NOW() - (#{queuedStuckMinutes} || ' minutes')::INTERVAL
                )
                OR (
                  COALESCE(status, 'PENDING') = 'RUNNING'
                  AND updated_at < NOW() - (#{runningStuckMinutes} || ' minutes')::INTERVAL
                )
              )
            """)
    int queueTask(@Param("taskId") UUID taskId,
                  @Param("queuedStuckMinutes") int queuedStuckMinutes,
                  @Param("runningStuckMinutes") int runningStuckMinutes);

    @Update("""
            UPDATE pipeline_tasks
            SET status = 'RUNNING',
                updated_at = NOW()
            WHERE id = #{taskId}
              AND status = 'QUEUED'
            """)
    int claimQueuedTask(@Param("taskId") UUID taskId);

    @Update("""
            UPDATE pipeline_tasks
            SET status = 'PENDING',
                updated_at = NOW()
            WHERE id = #{taskId}
              AND status = 'QUEUED'
            """)
    int releaseQueuedTask(@Param("taskId") UUID taskId);
}
