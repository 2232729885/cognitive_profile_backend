package com.idata.profile.agentproxy.dto.t5;

import lombok.Data;

import java.util.UUID;

/**
 * T5画像补全Agent：全量画像生成请求。
 * 重要：T5是定时任务批量调用（batch.profile.PersonProfileGenerationJob），
 * 一次性生成全部15维度，不做差量补全，不存在"传入已有画像让T5补充缺失字段"这种用法。
 */
@Data
public class T5GenerateProfileRequest {
    private UUID personId;
    // T5内部自行从Neo4j查询该人物的完整属性、关联内容、关系网络，
    // 这里不需要把这些数据打包传过去，只传personId让T5自己查
}
