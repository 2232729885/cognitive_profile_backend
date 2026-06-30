package com.idata.profile.agentproxy.dto.t1;

import lombok.Data;

/**
 * T1自动标注响应。9维度标注结果，写入 media_contents 对应字段。
 */
@Data
public class T1AnnotateResponse {
    private String topicCategory;
    private String topicSubcategory;
    private java.math.BigDecimal eventHeatScore;
    private String sentimentLabel;       // positive|negative|neutral
    private java.math.BigDecimal sentimentScore;
    private String stanceLabel;          // support|oppose|neutral
    private String stanceTarget;
    private java.math.BigDecimal aigcScore;
    private String aigcType;             // human|ai_text|ai_image|ai_mixed
    private Object entitiesHint;         // 传给T2的实体提示
    private String narrativeHint;        // 传给T2的叙事线索
    private String modelVersion;
    private String raw;                  // 完整原始响应，存入raw_records.t1_output
}
