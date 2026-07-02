package com.idata.profile.entity.profile;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.idata.profile.infra.mybatis.StringArrayTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@TableName(value = "person_profiles", autoResultMap = true)
public class PersonProfile {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    private UUID personId;
    private Integer portraitVersion;
    private String status;

    private String politicalOrientation;
    private BigDecimal politicalScore;
    private BigDecimal politicalConfidence;

    private String emotionProfile;
    private String stanceProfile;
    private String activeTimePattern;
    private BigDecimal postFrequencyDaily;
    private BigDecimal contentOriginalRatio;

    private String bendProfile;

    private BigDecimal influenceScore;
    private BigDecimal reachScore;
    private BigDecimal viralityScore;

    private String mbtiType;
    private BigDecimal mbtiConfidence;

    private String decisionStyle;
    private String languageStyle;

    @TableField(typeHandler = StringArrayTypeHandler.class)
    private String[] interestDomains;

    private String coordinationNetwork;
    private String preferredNarratives;

    private String targetType;
    private BigDecimal targetConfidence;
    private String targetEvidence;

    private String hiddenRelations;

    private String manipulationRisk;
    private BigDecimal manipulationScore;

    private String modelVersions;
    private OffsetDateTime generatedAt;
    private OffsetDateTime reviewedAt;
    private UUID reviewerId;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
