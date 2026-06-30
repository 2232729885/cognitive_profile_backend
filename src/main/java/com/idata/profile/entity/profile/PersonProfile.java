package com.idata.profile.entity.profile;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * L4画像层：人物全息画像，15维度。
 *
 * 重要：定时任务（batch.profile.PersonProfileGenerationJob）批量生成，
 * 不做差量补全。T5一次性生成全量15维度画像，新版本直接status='active'，
 * 旧版本自动归档为'archived'。不存在'pending_review'中间态，
 * 不存在补全相关字段（completion_status/completeness_score等已在设计阶段移除）。
 *
 * 同一personId只能有一条status='active'的记录（DB唯一索引约束）。
 *
 * 对应表：person_profiles
 */
@Data
@TableName("person_profiles")
public class PersonProfile {

    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;

    private UUID personId;
    private Integer portraitVersion;    // 每次重新生成自增
    private String status;              // active|archived|draft

    // 维度1：政治倾向
    private String politicalOrientation;
    private BigDecimal politicalScore;       // -100(亲西方)~100(反西方)
    private BigDecimal politicalConfidence;

    // 维度2：情感特征（10种复杂情感）
    private String emotionProfile;           // JSONB

    // 维度3：关键地缘政治立场
    private String stanceProfile;            // JSONB: [{topic,stance,confidence}]

    // 维度4：行为模式
    private String activeTimePattern;        // JSONB
    private BigDecimal postFrequencyDaily;
    private BigDecimal contentOriginalRatio;

    // 维度5：BEND手法分布
    private String bendProfile;              // JSONB: {Distort,Dismiss,Amplify,Narrativize,other}

    // 维度6：影响力
    private BigDecimal influenceScore;
    private BigDecimal reachScore;
    private BigDecimal viralityScore;

    // 维度7：MBTI推断
    private String mbtiType;
    private BigDecimal mbtiConfidence;

    // 维度8/9：决策风格、语言风格
    private String decisionStyle;            // aggressive|cautious|opportunistic|ideological
    private String languageStyle;            // inciting|rational|emotional|neutral

    // 维度10：兴趣领域
    private String[] interestDomains;

    // 维度11/12：协同网络、叙事偏好
    private String coordinationNetwork;      // JSONB
    private String preferredNarratives;      // JSONB

    // 维度13：操控目标类型（T6识别结果）
    private String targetType;               // T00~T10
    private BigDecimal targetConfidence;
    private String targetEvidence;

    // 维度14：隐性关系（对应任务书"稀疏场景隐性关系预测85%"指标）
    private String hiddenRelations;          // JSONB

    // 维度15：认知操控风险
    private String manipulationRisk;         // critical|high|medium|low
    private BigDecimal manipulationScore;

    private String modelVersions;            // JSONB，用于评测复现
    private OffsetDateTime generatedAt;
    private OffsetDateTime reviewedAt;       // 人工直接修正画像字段时的操作时间
    private UUID reviewerId;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
