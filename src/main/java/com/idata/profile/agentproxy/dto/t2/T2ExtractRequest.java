package com.idata.profile.agentproxy.dto.t2;

import lombok.Data;

/**
 * T2信息抽取请求。
 * 输入：bodyText + T1的entitiesHint/narrativeHint + 传播链字段。
 * 传播链字段（parentContentId/repostOfContentId/quotedContentId/mentions）
 * 用于T2推导内容间关系，不依赖单独的interaction明细数据。
 */
@Data
public class T2ExtractRequest {
    private String bodyText;
    private Object entitiesHint;
    private String narrativeHint;
    private String[] hashtags;
    private String[] mentions;
    private String parentContentId;
    private String repostOfContentId;
    private String quotedContentId;
}
