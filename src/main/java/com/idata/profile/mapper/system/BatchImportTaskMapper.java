package com.idata.profile.mapper.system;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.idata.profile.entity.system.BatchImportTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BatchImportTaskMapper extends BaseMapper<BatchImportTask> {

    @Select("SELECT * FROM batch_import_tasks WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT #{limit}")
    List<BatchImportTask> selectPendingTasks(@Param("limit") int limit);
}
