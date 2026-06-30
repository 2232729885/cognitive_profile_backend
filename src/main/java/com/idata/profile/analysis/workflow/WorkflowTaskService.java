package com.idata.profile.analysis.workflow;

import com.idata.profile.mapper.task.WorkflowTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** workflow_tasks 的CRUD和状态流转管理。 */
@Service
@RequiredArgsConstructor
public class WorkflowTaskService {

    private final WorkflowTaskMapper workflowTaskMapper;
    // TODO: 创建任务、更新执行步骤记录(steps字段)、保存最终结论
}
