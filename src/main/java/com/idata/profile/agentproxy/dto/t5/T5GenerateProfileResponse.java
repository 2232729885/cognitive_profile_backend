package com.idata.profile.agentproxy.dto.t5;

import lombok.Data;

import java.math.BigDecimal;

/**
 * T5画像补全响应。15维度全量画像数据，字段与 entity.profile.PersonProfile 一一对应，
 * 直接覆盖写入，见 docs/01-CODEGEN-CONTEXT.md 3.6节伪代码。
 */
@Data
public class T5GenerateProfileResponse {
    private String politicalOrientation;
    private BigDecimal politicalScore;
    private BigDecimal politicalConfidence;
    private Object emotionProfile;
    private Object stanceProfile;
    private Object activeTimePattern;
    private BigDecimal postFrequencyDaily;
    private BigDecimal contentOriginalRatio;
    private Object bendProfile;
    private BigDecimal influenceScore;
    private BigDecimal reachScore;
    private BigDecimal viralityScore;
    private String mbtiType;
    private BigDecimal mbtiConfidence;
    private String decisionStyle;
    private String languageStyle;
    private String[] interestDomains;
    private Object coordinationNetwork;
    private Object preferredNarratives;
    private String targetType;
    private BigDecimal targetConfidence;
    private String targetEvidence;
    private Object hiddenRelations;
    private String manipulationRisk;
    private BigDecimal manipulationScore;
    private Object modelVersions;
}
