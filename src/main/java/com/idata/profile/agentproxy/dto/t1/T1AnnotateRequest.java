package com.idata.profile.agentproxy.dto.t1;

import lombok.Data;

/**
 * T1自动标注请求。
 * 输入: media_contents.bodyText + rawPayload（供T1参考完整上下文）。
 * 见 docs/01-CODEGEN-CONTEXT.md 3.1节 runT1 伪代码。
 */
@Data
public class T1AnnotateRequest {
    private String bodyText;
    private String rawPayload;   // JSONB原文，按需传递平台特定字段
    private String language;
    private String platform;
}
