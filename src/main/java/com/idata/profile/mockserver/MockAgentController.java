package com.idata.profile.mockserver;

import com.idata.profile.agentproxy.dto.t1.T1AnnotateRequest;
import com.idata.profile.agentproxy.dto.t1.T1AnnotateResponse;
import com.idata.profile.agentproxy.dto.t2.T2ExtractRequest;
import com.idata.profile.agentproxy.dto.t2.T2ExtractResponse;
import com.idata.profile.agentproxy.dto.t3.T3ResolveBatchRequest;
import com.idata.profile.agentproxy.dto.t3.T3ResolveBatchResponse;
import com.idata.profile.agentproxy.dto.t4.T4EmbeddingRequest;
import com.idata.profile.agentproxy.dto.t4.T4EmbeddingResponse;
import com.idata.profile.agentproxy.dto.t5.T5GenerateProfileRequest;
import com.idata.profile.agentproxy.dto.t5.T5GenerateProfileResponse;
import com.idata.profile.agentproxy.dto.t6.T6IdentifyRequest;
import com.idata.profile.agentproxy.dto.t6.T6IdentifyResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@Profile("mock")
@RequiredArgsConstructor
public class MockAgentController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @PostMapping("/mock/t1/annotate_text")
    public T1AnnotateResponse annotateText(@RequestBody T1AnnotateRequest request) {
        log.info("[MOCK-T1] annotate_text, textLength={}",
                request.getText() != null ? request.getText().length() : 0);

        T1AnnotateResponse resp = new T1AnnotateResponse();
        resp.setSchemaVersion("t1_annotation_v0.5");
        resp.setInputReference(buildInputReference(request, "text"));
        resp.setLanguage(request.getLanguage() != null ? request.getLanguage() : "zh");

        T1AnnotateResponse.AigcDetection.TextAigcDetection textAigc =
                new T1AnnotateResponse.AigcDetection.TextAigcDetection();
        textAigc.setTextAigcLabel("human_generated");
        textAigc.setTextAigcScore(0.15);
        textAigc.setTextAigcSignalLabels(List.of("none"));
        textAigc.setTextAigcConfidence(0.80);
        textAigc.setEvidenceIds(List.of("ev_001"));

        T1AnnotateResponse.AigcDetection.ImageAigcDetection imageAigcNa =
                new T1AnnotateResponse.AigcDetection.ImageAigcDetection();
        imageAigcNa.setImageAigcLabel("not_applicable");
        imageAigcNa.setImageAigcSignalLabels(List.of("none"));
        imageAigcNa.setEvidenceIds(List.of());

        T1AnnotateResponse.AigcDetection.VideoAigcDetection videoAigcNa =
                new T1AnnotateResponse.AigcDetection.VideoAigcDetection();
        videoAigcNa.setVideoAigcLabel("not_applicable");
        videoAigcNa.setVideoAigcSignalLabels(List.of("none"));
        videoAigcNa.setEvidenceIds(List.of());

        T1AnnotateResponse.AigcDetection.MultimodalAigcDetection multimodalNa =
                new T1AnnotateResponse.AigcDetection.MultimodalAigcDetection();
        multimodalNa.setMultimodalAigcLabel("not_applicable");
        multimodalNa.setModalityCombination("not_applicable");
        multimodalNa.setMultimodalSignalLabels(List.of("none"));
        multimodalNa.setEvidenceIds(List.of());

        T1AnnotateResponse.AigcDetection aigcDetection = new T1AnnotateResponse.AigcDetection();
        aigcDetection.setOverallAigcLabel("human_generated");
        aigcDetection.setOverallAigcScore(0.15);
        aigcDetection.setTextAigcDetection(textAigc);
        aigcDetection.setImageAigcDetection(imageAigcNa);
        aigcDetection.setVideoAigcDetection(videoAigcNa);
        aigcDetection.setMultimodalAigcDetection(multimodalNa);
        aigcDetection.setAigcDetectionConfidence(0.80);
        resp.setAigcDetection(aigcDetection);

        T1AnnotateResponse.Annotations.HighValueSubjective.Ideology ideology =
                new T1AnnotateResponse.Annotations.HighValueSubjective.Ideology();
        ideology.setIdeologyLabel("anti_western");
        ideology.setTargetEntityHintIds(List.of("ent_002"));
        ideology.setIdeologyConfidence(0.75);
        ideology.setEvidenceIds(List.of("ev_002"));

        T1AnnotateResponse.Annotations.HighValueSubjective.CoreStance coreStance =
                new T1AnnotateResponse.Annotations.HighValueSubjective.CoreStance();
        coreStance.setStanceLabel("oppose");
        coreStance.setStanceStrength("strong");
        coreStance.setCoreStanceConfidence(0.85);
        coreStance.setEvidenceIds(List.of("ev_002"));

        T1AnnotateResponse.Annotations.HighValueSubjective.EntityHintStance.StanceHolder stanceHolder =
                new T1AnnotateResponse.Annotations.HighValueSubjective.EntityHintStance.StanceHolder();
        stanceHolder.setStanceHolderId("ent_001");
        stanceHolder.setText("Leila Farzan");

        T1AnnotateResponse.Annotations.HighValueSubjective.EntityHintStance.StanceTarget stanceTarget =
                new T1AnnotateResponse.Annotations.HighValueSubjective.EntityHintStance.StanceTarget();
        stanceTarget.setStanceTargetId("ent_002");
        stanceTarget.setText("U.S. Central Command");

        T1AnnotateResponse.Annotations.HighValueSubjective.EntityHintStance entityHintStance =
                new T1AnnotateResponse.Annotations.HighValueSubjective.EntityHintStance();
        entityHintStance.setStanceUnitId("stance_001");
        entityHintStance.setStanceHolder(stanceHolder);
        entityHintStance.setStanceTarget(stanceTarget);
        entityHintStance.setStanceLabel("oppose");
        entityHintStance.setEvidenceIds(List.of("ev_002"));

        T1AnnotateResponse.Annotations.HighValueSubjective.PublicAttitude publicAttitude =
                new T1AnnotateResponse.Annotations.HighValueSubjective.PublicAttitude();
        publicAttitude.setPublicGroup("netizens");
        publicAttitude.setAttitudeLabel("critical");
        publicAttitude.setAttitudeIntensity("medium");
        publicAttitude.setPublicAttitudeConfidence(0.60);
        publicAttitude.setEvidenceIds(List.of("ev_003"));

        T1AnnotateResponse.Annotations.HighValueSubjective.OpinionEmotion opinionEmotion =
                new T1AnnotateResponse.Annotations.HighValueSubjective.OpinionEmotion();
        opinionEmotion.setSentimentPolarity("negative");
        opinionEmotion.setEmotionLabels(List.of("anger", "contempt"));
        opinionEmotion.setEmotionIntensity("medium");
        opinionEmotion.setOpinionEmotionConfidence(0.82);
        opinionEmotion.setEvidenceIds(List.of("ev_002"));

        T1AnnotateResponse.Annotations.HighValueSubjective.EventHeat eventHeat =
                new T1AnnotateResponse.Annotations.HighValueSubjective.EventHeat();
        eventHeat.setHeatLevel("medium");
        eventHeat.setHeatScore(55.0);
        eventHeat.setHeatSignalTypes(List.of("textual_heat_signal"));
        eventHeat.setEventHeatConfidence(0.70);
        eventHeat.setEvidenceIds(List.of("ev_004"));

        T1AnnotateResponse.Annotations.HighValueSubjective.LanguageStyle languageStyle =
                new T1AnnotateResponse.Annotations.HighValueSubjective.LanguageStyle();
        languageStyle.setStyleLabels(List.of("accusatory", "rational_analytical"));
        languageStyle.setLanguageStyleConfidence(0.70);
        languageStyle.setEvidenceIds(List.of("ev_002"));

        T1AnnotateResponse.Annotations.HighValueSubjective.ContentPurpose contentPurpose =
                new T1AnnotateResponse.Annotations.HighValueSubjective.ContentPurpose();
        contentPurpose.setPrimaryPurpose("opinion_expression");
        contentPurpose.setSecondaryPurposes(List.of("attack_or_smear"));
        contentPurpose.setContentPurposeConfidence(0.72);
        contentPurpose.setEvidenceIds(List.of("ev_002"));

        T1AnnotateResponse.Annotations.HighValueSubjective.RiskLevel riskLevel =
                new T1AnnotateResponse.Annotations.HighValueSubjective.RiskLevel();
        riskLevel.setRiskLabel("medium");
        riskLevel.setRiskTypes(List.of("polarization"));
        riskLevel.setRiskLevelConfidence(0.68);
        riskLevel.setEvidenceIds(List.of("ev_003"));

        T1AnnotateResponse.Annotations.HighValueSubjective highValueSubjective =
                new T1AnnotateResponse.Annotations.HighValueSubjective();
        highValueSubjective.setIdeology(ideology);
        highValueSubjective.setCoreStance(coreStance);
        highValueSubjective.setEntitiesHintStance(List.of(entityHintStance));
        highValueSubjective.setPublicAttitude(publicAttitude);
        highValueSubjective.setOpinionEmotion(opinionEmotion);
        highValueSubjective.setEventHeat(eventHeat);
        highValueSubjective.setLanguageStyle(languageStyle);
        highValueSubjective.setContentPurpose(contentPurpose);
        highValueSubjective.setRiskLevel(riskLevel);

        T1AnnotateResponse.Annotations.BasicObjective.TopicTags topicTags =
                new T1AnnotateResponse.Annotations.BasicObjective.TopicTags();
        topicTags.setPrimaryDomain("military");
        topicTags.setSubtopicTags(List.of("geopolitics", "gulf_security"));
        topicTags.setTopicTagsConfidence(0.90);
        topicTags.setEvidenceIds(List.of("ev_001"));

        T1AnnotateResponse.Annotations.BasicObjective.AccountType accountType =
                new T1AnnotateResponse.Annotations.BasicObjective.AccountType();
        accountType.setPrimaryAccountCategory("unknown");
        accountType.setAccountSubtypeTags(List.of());
        accountType.setAutomationSuspicion("unclear");
        accountType.setAccountTypeConfidence(null);
        accountType.setEvidenceIds(List.of());

        T1AnnotateResponse.Annotations.BasicObjective.EntityHint entityHint1 =
                new T1AnnotateResponse.Annotations.BasicObjective.EntityHint();
        entityHint1.setEntityHintId("ent_001");
        entityHint1.setText("Leila Farzan");
        entityHint1.setTypeHint("persons");
        entityHint1.setSpan(List.of(0, 12));
        entityHint1.setEntityHintConfidence(0.90);
        entityHint1.setEvidenceIds(List.of("ev_001"));

        T1AnnotateResponse.Annotations.BasicObjective.EntityHint entityHint2 =
                new T1AnnotateResponse.Annotations.BasicObjective.EntityHint();
        entityHint2.setEntityHintId("ent_002");
        entityHint2.setText("U.S. Central Command");
        entityHint2.setTypeHint("organizations");
        entityHint2.setSpan(List.of(20, 41));
        entityHint2.setEntityHintConfidence(0.92);
        entityHint2.setEvidenceIds(List.of("ev_001"));

        T1AnnotateResponse.Annotations.BasicObjective.Keyword keyword1 =
                new T1AnnotateResponse.Annotations.BasicObjective.Keyword();
        keyword1.setKeywordText("霍尔木兹海峡");
        keyword1.setSource("text");
        keyword1.setSpan(List.of(5, 11));
        keyword1.setKeywordConfidence(0.88);
        keyword1.setEvidenceIds(List.of("ev_001"));

        T1AnnotateResponse.Annotations.BasicObjective.Keyword keyword2 =
                new T1AnnotateResponse.Annotations.BasicObjective.Keyword();
        keyword2.setKeywordText("军事对峙");
        keyword2.setSource("text");
        keyword2.setSpan(List.of(12, 16));
        keyword2.setKeywordConfidence(0.85);
        keyword2.setEvidenceIds(List.of("ev_001"));

        T1AnnotateResponse.Annotations.BasicObjective.Summary summary =
                new T1AnnotateResponse.Annotations.BasicObjective.Summary();
        summary.setSummaryText("该内容围绕霍尔木兹海峡军事对峙议题，对外部势力行为表达质疑和反对。");
        summary.setSummaryConfidence(0.85);

        T1AnnotateResponse.Annotations.BasicObjective.EventType eventType =
                new T1AnnotateResponse.Annotations.BasicObjective.EventType();
        eventType.setEventTypeLabel("military_conflict");
        eventType.setEventTypeConfidence(0.80);
        eventType.setEvidenceIds(List.of("ev_001"));

        T1AnnotateResponse.Annotations.BasicObjective basicObjective =
                new T1AnnotateResponse.Annotations.BasicObjective();
        basicObjective.setTopicTags(topicTags);
        basicObjective.setAccountType(accountType);
        basicObjective.setEntitiesHint(List.of(entityHint1, entityHint2));
        basicObjective.setKeywords(List.of(keyword1, keyword2));
        basicObjective.setSummary(summary);
        basicObjective.setEventType(eventType);

        T1AnnotateResponse.Annotations annotations = new T1AnnotateResponse.Annotations();
        annotations.setHighValueSubjective(highValueSubjective);
        annotations.setBasicObjective(basicObjective);
        resp.setAnnotations(annotations);

        T1AnnotateResponse.EvidenceClue ev1 = new T1AnnotateResponse.EvidenceClue();
        ev1.setEvidenceId("ev_001");
        ev1.setEvidenceType("text_span");
        ev1.setSource("text");
        ev1.setEvidenceText("Leila Farzan, an analyst related to U.S. Central Command");
        ev1.setSpan(List.of(0, 41));

        T1AnnotateResponse.EvidenceClue ev2 = new T1AnnotateResponse.EvidenceClue();
        ev2.setEvidenceId("ev_002");
        ev2.setEvidenceType("text_span");
        ev2.setSource("text");
        ev2.setEvidenceText("对外部势力行为表达质疑和反对，存在对立放大表述");
        ev2.setSpan(List.of(42, 66));

        T1AnnotateResponse.EvidenceClue ev3 = new T1AnnotateResponse.EvidenceClue();
        ev3.setEvidenceId("ev_003");
        ev3.setEvidenceType("text_span");
        ev3.setSource("text");
        ev3.setEvidenceText("网民对此反应强烈，批评声音较多");
        ev3.setSpan(List.of(67, 82));

        T1AnnotateResponse.EvidenceClue ev4 = new T1AnnotateResponse.EvidenceClue();
        ev4.setEvidenceId("ev_004");
        ev4.setEvidenceType("text_span");
        ev4.setSource("text");
        ev4.setEvidenceText("事件在社交媒体上持续发酵");
        ev4.setSpan(List.of(83, 95));

        resp.setEvidenceClues(List.of(ev1, ev2, ev3, ev4));

        T1AnnotateResponse.QualityControl qualityControl = new T1AnnotateResponse.QualityControl();
        qualityControl.setNeedHumanReview(false);
        qualityControl.setReviewReasons(List.of("none"));
        qualityControl.setFailedModules(List.of("none"));
        resp.setQualityControl(qualityControl);

        resp.setOverallConfidence(0.80);
        resp.setProcessedAt(java.time.OffsetDateTime.now().toString());
        return resp;
    }

    @PostMapping("/mock/t1/annotate_image")
    public T1AnnotateResponse annotateImage(@RequestBody T1AnnotateRequest request) {
        log.info("[MOCK-T1] annotate_image, imageUrl={}, hasImageData={}",
                request.getImageUrl(), request.getImageData() != null);

        T1AnnotateResponse resp = new T1AnnotateResponse();
        resp.setSchemaVersion("t1_annotation_v0.5");
        resp.setInputReference(buildInputReference(request, "image"));
        resp.setLanguage(request.getLanguage() != null ? request.getLanguage() : "zh");

        T1AnnotateResponse.AigcDetection.TextAigcDetection textAigcNa =
                new T1AnnotateResponse.AigcDetection.TextAigcDetection();
        textAigcNa.setTextAigcLabel("not_applicable");
        textAigcNa.setTextAigcSignalLabels(List.of("none"));
        textAigcNa.setEvidenceIds(List.of());

        T1AnnotateResponse.AigcDetection.ImageAigcDetection imageAigc =
                new T1AnnotateResponse.AigcDetection.ImageAigcDetection();
        imageAigc.setImageAigcLabel("human_generated");
        imageAigc.setImageAigcScore(0.20);
        imageAigc.setImageAigcSignalLabels(List.of("none"));
        imageAigc.setImageAigcConfidence(0.80);
        imageAigc.setEvidenceIds(List.of("ev_img_001"));

        T1AnnotateResponse.AigcDetection.VideoAigcDetection videoAigcNa =
                new T1AnnotateResponse.AigcDetection.VideoAigcDetection();
        videoAigcNa.setVideoAigcLabel("not_applicable");
        videoAigcNa.setVideoAigcSignalLabels(List.of("none"));
        videoAigcNa.setEvidenceIds(List.of());

        T1AnnotateResponse.AigcDetection.MultimodalAigcDetection multimodal =
                new T1AnnotateResponse.AigcDetection.MultimodalAigcDetection();
        multimodal.setMultimodalAigcLabel("consistent");
        multimodal.setModalityCombination("image_text_ocr");
        multimodal.setMultimodalSignalLabels(List.of("none"));
        multimodal.setMultimodalAigcConfidence(0.75);
        multimodal.setEvidenceIds(List.of("ev_img_002"));

        T1AnnotateResponse.AigcDetection aigcDetection = new T1AnnotateResponse.AigcDetection();
        aigcDetection.setOverallAigcLabel("human_generated");
        aigcDetection.setOverallAigcScore(0.20);
        aigcDetection.setTextAigcDetection(textAigcNa);
        aigcDetection.setImageAigcDetection(imageAigc);
        aigcDetection.setVideoAigcDetection(videoAigcNa);
        aigcDetection.setMultimodalAigcDetection(multimodal);
        aigcDetection.setAigcDetectionConfidence(0.78);
        resp.setAigcDetection(aigcDetection);

        T1AnnotateResponse.Annotations.HighValueSubjective.Ideology ideology =
                new T1AnnotateResponse.Annotations.HighValueSubjective.Ideology();
        ideology.setIdeologyLabel("unclear");
        ideology.setTargetEntityHintIds(List.of());
        ideology.setEvidenceIds(List.of());

        T1AnnotateResponse.Annotations.HighValueSubjective.CoreStance coreStance =
                new T1AnnotateResponse.Annotations.HighValueSubjective.CoreStance();
        coreStance.setStanceLabel("unclear");
        coreStance.setStanceStrength("unclear");
        coreStance.setEvidenceIds(List.of());

        T1AnnotateResponse.Annotations.HighValueSubjective.PublicAttitude publicAttitude =
                new T1AnnotateResponse.Annotations.HighValueSubjective.PublicAttitude();
        publicAttitude.setPublicGroup("not_applicable");
        publicAttitude.setAttitudeLabel("not_applicable");
        publicAttitude.setAttitudeIntensity("not_applicable");
        publicAttitude.setEvidenceIds(List.of());

        T1AnnotateResponse.Annotations.HighValueSubjective.OpinionEmotion opinionEmotion =
                new T1AnnotateResponse.Annotations.HighValueSubjective.OpinionEmotion();
        opinionEmotion.setSentimentPolarity("neutral");
        opinionEmotion.setEmotionLabels(List.of("none"));
        opinionEmotion.setEmotionIntensity("unclear");
        opinionEmotion.setOpinionEmotionConfidence(0.40);
        opinionEmotion.setEvidenceIds(List.of("ev_img_002"));

        T1AnnotateResponse.Annotations.HighValueSubjective.EventHeat eventHeat =
                new T1AnnotateResponse.Annotations.HighValueSubjective.EventHeat();
        eventHeat.setHeatLevel("unclear");
        eventHeat.setHeatSignalTypes(List.of("unclear"));
        eventHeat.setEvidenceIds(List.of());

        T1AnnotateResponse.Annotations.HighValueSubjective.LanguageStyle languageStyle =
                new T1AnnotateResponse.Annotations.HighValueSubjective.LanguageStyle();
        languageStyle.setStyleLabels(List.of("unclear"));
        languageStyle.setEvidenceIds(List.of());

        T1AnnotateResponse.Annotations.HighValueSubjective.ContentPurpose contentPurpose =
                new T1AnnotateResponse.Annotations.HighValueSubjective.ContentPurpose();
        contentPurpose.setPrimaryPurpose("unclear");
        contentPurpose.setSecondaryPurposes(List.of());
        contentPurpose.setEvidenceIds(List.of());

        T1AnnotateResponse.Annotations.HighValueSubjective.RiskLevel riskLevel =
                new T1AnnotateResponse.Annotations.HighValueSubjective.RiskLevel();
        riskLevel.setRiskLabel("low");
        riskLevel.setRiskTypes(List.of("none"));
        riskLevel.setRiskLevelConfidence(0.50);
        riskLevel.setEvidenceIds(List.of("ev_img_001"));

        T1AnnotateResponse.Annotations.HighValueSubjective highValueSubjective =
                new T1AnnotateResponse.Annotations.HighValueSubjective();
        highValueSubjective.setIdeology(ideology);
        highValueSubjective.setCoreStance(coreStance);
        highValueSubjective.setEntitiesHintStance(List.of());
        highValueSubjective.setPublicAttitude(publicAttitude);
        highValueSubjective.setOpinionEmotion(opinionEmotion);
        highValueSubjective.setEventHeat(eventHeat);
        highValueSubjective.setLanguageStyle(languageStyle);
        highValueSubjective.setContentPurpose(contentPurpose);
        highValueSubjective.setRiskLevel(riskLevel);

        T1AnnotateResponse.Annotations.BasicObjective.TopicTags topicTags =
                new T1AnnotateResponse.Annotations.BasicObjective.TopicTags();
        topicTags.setPrimaryDomain("military");
        topicTags.setSubtopicTags(List.of("maritime"));
        topicTags.setTopicTagsConfidence(0.75);
        topicTags.setEvidenceIds(List.of("ev_img_001"));

        T1AnnotateResponse.Annotations.BasicObjective.AccountType accountType =
                new T1AnnotateResponse.Annotations.BasicObjective.AccountType();
        accountType.setPrimaryAccountCategory("unknown");
        accountType.setAccountSubtypeTags(List.of());
        accountType.setAutomationSuspicion("unclear");
        accountType.setEvidenceIds(List.of());

        T1AnnotateResponse.Annotations.BasicObjective.EntityHint shipHint =
                new T1AnnotateResponse.Annotations.BasicObjective.EntityHint();
        shipHint.setEntityHintId("ent_101");
        shipHint.setText("military ship");
        shipHint.setTypeHint("others");
        shipHint.setEntityHintConfidence(0.85);
        shipHint.setEvidenceIds(List.of("ev_img_001"));

        T1AnnotateResponse.Annotations.BasicObjective.Keyword ocrKeyword =
                new T1AnnotateResponse.Annotations.BasicObjective.Keyword();
        ocrKeyword.setKeywordText("HORMUZ 2026");
        ocrKeyword.setSource("image_ocr");
        ocrKeyword.setKeywordConfidence(0.90);
        ocrKeyword.setEvidenceIds(List.of("ev_img_002"));

        T1AnnotateResponse.Annotations.BasicObjective.Summary summary =
                new T1AnnotateResponse.Annotations.BasicObjective.Summary();
        summary.setSummaryText("图像内容为一艘船只的海上画面，画面中可见文字标注 \"HORMUZ 2026\"。");
        summary.setSummaryConfidence(0.70);

        T1AnnotateResponse.Annotations.BasicObjective.EventType eventType =
                new T1AnnotateResponse.Annotations.BasicObjective.EventType();
        eventType.setEventTypeLabel("unclear");
        eventType.setEvidenceIds(List.of());

        T1AnnotateResponse.Annotations.BasicObjective basicObjective =
                new T1AnnotateResponse.Annotations.BasicObjective();
        basicObjective.setTopicTags(topicTags);
        basicObjective.setAccountType(accountType);
        basicObjective.setEntitiesHint(List.of(shipHint));
        basicObjective.setKeywords(List.of(ocrKeyword));
        basicObjective.setSummary(summary);
        basicObjective.setEventType(eventType);

        T1AnnotateResponse.Annotations annotations = new T1AnnotateResponse.Annotations();
        annotations.setHighValueSubjective(highValueSubjective);
        annotations.setBasicObjective(basicObjective);
        resp.setAnnotations(annotations);

        T1AnnotateResponse.EvidenceClue.Region shipRegion = new T1AnnotateResponse.EvidenceClue.Region();
        shipRegion.setX(10.0);
        shipRegion.setY(20.0);
        shipRegion.setWidth(300.0);
        shipRegion.setHeight(200.0);

        T1AnnotateResponse.EvidenceClue evImg1 = new T1AnnotateResponse.EvidenceClue();
        evImg1.setEvidenceId("ev_img_001");
        evImg1.setEvidenceType("image_region");
        evImg1.setSource("image");
        evImg1.setMediaId("asset_001");
        evImg1.setRegion(shipRegion);

        T1AnnotateResponse.EvidenceClue.Region ocrRegion = new T1AnnotateResponse.EvidenceClue.Region();
        ocrRegion.setX(0.0);
        ocrRegion.setY(150.0);
        ocrRegion.setWidth(640.0);
        ocrRegion.setHeight(80.0);

        T1AnnotateResponse.EvidenceClue evImg2 = new T1AnnotateResponse.EvidenceClue();
        evImg2.setEvidenceId("ev_img_002");
        evImg2.setEvidenceType("image_region");
        evImg2.setSource("ocr");
        evImg2.setEvidenceText("HORMUZ 2026");
        evImg2.setMediaId("asset_001");
        evImg2.setRegion(ocrRegion);

        resp.setEvidenceClues(List.of(evImg1, evImg2));

        T1AnnotateResponse.QualityControl qualityControl = new T1AnnotateResponse.QualityControl();
        qualityControl.setNeedHumanReview(false);
        qualityControl.setReviewReasons(List.of("insufficient_context"));
        qualityControl.setFailedModules(List.of("none"));
        resp.setQualityControl(qualityControl);

        resp.setOverallConfidence(0.60);
        resp.setProcessedAt(java.time.OffsetDateTime.now().toString());
        return resp;
    }

    private T1AnnotateResponse.InputReference buildInputReference(T1AnnotateRequest request, String contentType) {
        T1AnnotateResponse.InputReference ref = new T1AnnotateResponse.InputReference();
        T1AnnotateRequest.Context context = request.getContext();
        ref.setContentId(context != null ? context.getDocId() : null);
        ref.setContentType(contentType);
        ref.setPlatform(context != null ? context.getPlatform() : null);
        ref.setUrl(context != null ? context.getUrl() : null);
        ref.setAuthorId(context != null ? context.getAuthorHandle() : null);
        ref.setCreatedAt(context != null ? context.getPublishedAt() : null);
        return ref;
    }

    @PostMapping("/mock/t2/extract_entities")
    public T2ExtractResponse extractEntities(@RequestBody T2ExtractRequest request) {
        log.info("[MOCK-T2] extract_entities, textLength={}, hasAnnotation={}",
                request.getText() != null ? request.getText().length() : 0,
                request.getAnnotation() != null);

        T2ExtractResponse.ExtractedMention person = new T2ExtractResponse.ExtractedMention();
        person.setMentionId("m1");
        person.setName("Leila Farzan");
        person.setType("person");
        person.setNormalizedName("Leila Farzan");
        person.setImportanceScore(88.0);
        person.setConfidence(0.92);
        person.setAliases(List.of("L. Farzan", "莱拉·法尔赞"));
        person.setAttributes(Map.of());

        T2ExtractResponse.ExtractedMention organization = new T2ExtractResponse.ExtractedMention();
        organization.setMentionId("m3");
        organization.setName("U.S. Central Command");
        organization.setType("organization");
        organization.setNormalizedName("U.S. Central Command");
        organization.setImportanceScore(84.0);
        organization.setConfidence(0.95);
        organization.setAliases(List.of("CENTCOM"));
        organization.setAttributes(Map.of());

        T2ExtractResponse.ExtractedMention location = new T2ExtractResponse.ExtractedMention();
        location.setMentionId("m4");
        location.setName("Strait of Hormuz");
        location.setType("location");
        location.setNormalizedName("Strait of Hormuz");
        location.setImportanceScore(85.0);
        location.setConfidence(0.95);
        location.setAliases(List.of("霍尔木兹海峡"));
        location.setAttributes(Map.of());

        T2ExtractResponse.ExtractedMention event = new T2ExtractResponse.ExtractedMention();
        event.setMentionId("e1");
        event.setName("2026 Persian Gulf Military Standoff");
        event.setType("event");
        event.setNormalizedName("2026 Persian Gulf Military Standoff");
        event.setImportanceScore(90.0);
        event.setConfidence(0.90);
        event.setAliases(List.of());
        event.setAttributes(Map.of(
                "eventType", "military",
                "eventTimeStart", "2026-06-01T00:00:00Z"));

        T2ExtractResponse.ExtractedRelationMention rel1 = new T2ExtractResponse.ExtractedRelationMention();
        rel1.setRelationMentionId("r1");
        rel1.setSubjectMentionId("m1");
        rel1.setPredicate("BELONGS_TO");
        rel1.setObjectMentionId("m3");
        rel1.setConfidence(0.78);
        rel1.setEvidence("Leila Farzan is described as an analyst related to U.S. Central Command");

        T2ExtractResponse.ExtractedRelationMention rel2 = new T2ExtractResponse.ExtractedRelationMention();
        rel2.setRelationMentionId("r2");
        rel2.setSubjectMentionId("e1");
        rel2.setPredicate("EVENT_OCCURRED_AT");
        rel2.setObjectMentionId("m4");
        rel2.setConfidence(0.90);
        rel2.setEvidence("The military standoff is located near the Strait of Hormuz");

        T2ExtractResponse.ExtractedRelationMention rel3 = new T2ExtractResponse.ExtractedRelationMention();
        rel3.setRelationMentionId("r3");
        rel3.setSubjectMentionId("e1");
        rel3.setPredicate("EVENT_INVOLVES_ENTITY");
        rel3.setObjectMentionId("m3");
        rel3.setConfidence(0.95);
        rel3.setEvidence("U.S. Central Command is involved in the standoff");

        T2ExtractResponse resp = new T2ExtractResponse();
        resp.setDocId(request.getDocId());
        resp.setEntities(List.of(person, organization, location, event));
        resp.setRelations(List.of(rel1, rel2, rel3));
        resp.setResolvedAuthorAccountId(null);
        resp.setModelVersion("mock-t2-v2.0");
        resp.setRaw(toJson(resp));
        return resp;
    }

    @PostMapping("/mock/t3/resolve_batch")
    public T3ResolveBatchResponse resolveBatch(@RequestBody T3ResolveBatchRequest request) {
        int itemCount = request.getItems() != null ? request.getItems().size() : 0;
        log.info("[MOCK-T3] resolve_batch, itemCount={}", itemCount);

        List<T3ResolveBatchResponse.ResolveResult> results =
                request.getItems() == null ? List.of() : request.getItems().stream()
                        .map(item -> {
                            T3ResolveBatchResponse.ResolveResult result =
                                    new T3ResolveBatchResponse.ResolveResult();
                            String mentionId = item.getMention() != null ? item.getMention().getMentionId() : null;
                            result.setMentionId(mentionId);

                            double autoMergeThreshold = request.getStrategy() != null
                                    && request.getStrategy().getAutoMergeThreshold() != null
                                    ? request.getStrategy().getAutoMergeThreshold() : 0.9D;
                            double reviewThreshold = request.getStrategy() != null
                                    && request.getStrategy().getReviewThreshold() != null
                                    ? request.getStrategy().getReviewThreshold() : 0.6D;

                            T3ResolveBatchRequest.Candidate top = item.getCandidates() != null
                                    && !item.getCandidates().isEmpty()
                                    ? item.getCandidates().get(0) : null;
                            double topScore = top != null && top.getScore() != null ? top.getScore() : 0D;

                            if (top != null && topScore >= autoMergeThreshold) {
                                result.setAction("MERGE");
                                result.setMatchedEntityId(top.getEntityId());
                                result.setMatchMethod("mock_candidate_top1");
                                result.setReason("mock resolver selected the highest ranked candidate");
                            } else if (top != null && topScore >= reviewThreshold) {
                                result.setAction("REVIEW");
                                result.setMatchedEntityId(top.getEntityId());
                                result.setMatchMethod("mock_candidate_top1");
                                result.setReason("mock resolver selected the highest ranked candidate");
                            } else {
                                result.setAction("CREATE");
                                result.setMatchMethod(top != null ? "low_score_candidate" : "no_candidate");
                                result.setReason(top != null
                                        ? "top candidate score below review threshold"
                                        : "no candidate returned by backend retrieval");
                            }
                            result.setScore(topScore);
                            result.setConfidence(topScore);
                            return result;
                        })
                        .toList();

        T3ResolveBatchResponse resp = new T3ResolveBatchResponse();
        resp.setResults(results);
        resp.setModelVersion("mock-t3-resolve-v2.0");
        return resp;
    }

    @PostMapping("/mock/t4/generate_text_embedding")
    public T4EmbeddingResponse generateTextEmbedding(@RequestBody T4EmbeddingRequest request) {
        log.info("[MOCK-T4] generate_text_embedding, textLength={}",
                request.getText() != null ? request.getText().length() : 0);
        return buildEmbeddingResponse(request.getText());
    }

    @PostMapping("/mock/t4/generate_image_embedding")
    public T4EmbeddingResponse generateImageEmbedding(@RequestBody T4EmbeddingRequest request) {
        log.info("[MOCK-T4] generate_image_embedding, imageUrl={}", request.getImageUrl());
        return buildEmbeddingResponse(request.getImageUrl());
    }

    @PostMapping("/mock/t5/complete_profile")
    public T5GenerateProfileResponse generateFullProfile(@RequestBody T5GenerateProfileRequest request) {
        log.info("[MOCK-T5] complete_profile, targetId={}, targetType={}",
                request.getTargetId(), request.getTargetType());

        T5GenerateProfileResponse resp = new T5GenerateProfileResponse();
        resp.setPoliticalOrientation("anti_west");
        resp.setPoliticalScore(new BigDecimal("65.00"));
        resp.setPoliticalConfidence(new BigDecimal("0.820"));
        resp.setEmotionProfile(Map.of(
                "moral_outrage", 0.89,
                "resentment", 0.76,
                "fear_inducing", 0.71
        ));
        resp.setStanceProfile(List.of(Map.of("topic", "mock_topic", "stance", "strongly_oppose", "confidence", 0.91)));
        resp.setActiveTimePattern(Map.of("14", 0.15, "20", 0.22));
        resp.setPostFrequencyDaily(new BigDecimal("4.20"));
        resp.setContentOriginalRatio(new BigDecimal("0.350"));
        resp.setBendProfile(Map.of("Distort", 0.41, "Dismiss", 0.28, "Amplify", 0.19));
        resp.setInfluenceScore(new BigDecimal("78.00"));
        resp.setReachScore(new BigDecimal("65.00"));
        resp.setViralityScore(new BigDecimal("58.00"));
        resp.setMbtiType("ENTJ");
        resp.setMbtiConfidence(new BigDecimal("0.670"));
        resp.setDecisionStyle("aggressive");
        resp.setLanguageStyle("inciting");
        resp.setInterestDomains(new String[]{"geopolitics", "military", "economics"});
        resp.setCoordinationNetwork(List.of(Map.of("entityId", UUID.randomUUID().toString(), "confidence", 0.87)));
        resp.setPreferredNarratives(List.of(Map.of("narrativeId", UUID.randomUUID().toString(), "frequency", 23)));
        resp.setTargetType("T07");
        resp.setTargetConfidence(new BigDecimal("0.880"));
        resp.setTargetEvidence("mock evidence");
        resp.setHiddenRelations(List.of(Map.of(
                "targetId", UUID.randomUUID().toString(),
                "relationType", "POTENTIAL_SUBORDINATE_TO",
                "confidence", 0.63
        )));
        resp.setManipulationRisk("high");
        resp.setManipulationScore(new BigDecimal("82.00"));
        resp.setModelVersions(Map.of("t5_model", "mock-t5-v1.0"));
        return resp;
    }

    @PostMapping("/mock/t6/identify_targets")
    public T6IdentifyResponse identifyTargets(@RequestBody T6IdentifyRequest request) {
        log.info("[MOCK-T6] identify_targets, narrativeId={}, accountCount={}, contentCount={}",
                request.getNarrativeId(),
                request.getSocialAccounts() != null ? request.getSocialAccounts().size() : 0,
                request.getMediaContents() != null ? request.getMediaContents().size() : 0);

        String firstAccountId = request.getSocialAccounts() != null && !request.getSocialAccounts().isEmpty()
                ? request.getSocialAccounts().get(0).getAccountId()
                : UUID.randomUUID().toString();

        T6IdentifyResponse.AccountIdentifyResult accountResult =
                new T6IdentifyResponse.AccountIdentifyResult();
        accountResult.setAccountId(firstAccountId);
        accountResult.setTargetType("T08");
        accountResult.setTargetTypeName("协调网络核心节点");
        accountResult.setConfidence(new BigDecimal("0.87"));
        accountResult.setCsiScore(new BigDecimal("0.72"));

        T6IdentifyResponse.MatchEvidence evidence = new T6IdentifyResponse.MatchEvidence();
        evidence.setMatchedTacticId("T08");
        evidence.setDistributionSimilarity(0.91);
        evidence.setSequenceSimilarity(0.83);
        evidence.setCombinedScore(0.87);
        evidence.setEvidence("mock evidence");
        accountResult.setEvidence(evidence);

        int accountCount = request.getSocialAccounts() != null ? request.getSocialAccounts().size() : 0;
        T6IdentifyResponse.Summary summary = new T6IdentifyResponse.Summary();
        summary.setNarrativeId(request.getNarrativeId());
        summary.setTotalAccounts(accountCount);
        summary.setT00Count(Math.max(0, accountCount - 1));
        summary.setSuspectCount(0);
        summary.setIdentifiedCount(Math.min(1, accountCount));
        summary.setUnknownCount(0);
        summary.setGroupCount(0);
        summary.setProcessingTimeMs(453L);

        T6IdentifyResponse resp = new T6IdentifyResponse();
        resp.setAccountIdentifyResult(List.of(accountResult));
        resp.setEntityIdentifyResult(List.of());
        resp.setGroupIdentifyResult(List.of());
        resp.setSummary(summary);
        return resp;
    }

    private T4EmbeddingResponse buildEmbeddingResponse(String seed) {
        int dim = 4096;
        float[] embedding = new float[dim];
        long seedHash = seed != null ? seed.hashCode() : 0;
        java.util.Random random = new java.util.Random(seedHash);
        for (int i = 0; i < dim; i++) {
            embedding[i] = (random.nextFloat() * 2) - 1;
        }
        T4EmbeddingResponse resp = new T4EmbeddingResponse();
        resp.setEmbedding(embedding);
        resp.setModelVersion("mock-qwen3-vl-embedding-8b");
        return resp;
    }

    private String toJson(Object obj) {
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (JacksonException e) {
            return "{\"serializationError\":\"" + e.getClass().getSimpleName() + "\"}";
        }
    }
}
