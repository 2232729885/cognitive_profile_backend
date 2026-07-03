package com.idata.profile.batch.profile;

import com.idata.profile.agentproxy.AgentProxyClient;
import com.idata.profile.agentproxy.dto.t5.T5GenerateProfileRequest;
import com.idata.profile.agentproxy.dto.t5.T5GenerateProfileResponse;
import com.idata.profile.entity.graph.Person;
import com.idata.profile.entity.profile.PersonProfile;
import com.idata.profile.mapper.graph.PersonMapper;
import com.idata.profile.mapper.profile.PersonProfileMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 画像批量生成定时任务。每日凌晨执行，不做差量补全，
 * T5一次性生成全量15维度画像。见 docs/01-CODEGEN-CONTEXT.md 3.6节伪代码。
 *
 * 不是用户触发，与analysis线（用户触发的分析任务）完全独立。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonProfileGenerationJob {

    private static final int BATCH_LIMIT = 100;  // 每轮处理数量，避免一次性压垮T5

    private final PersonMapper personMapper;
    private final PersonProfileMapper personProfileMapper;
    private final AgentProxyClient agentProxyClient;

//    @Scheduled(cron = "0 0 2 * * *")  // 每日凌晨2点
    @Scheduled(fixedDelay = 2 * 60 * 1000)
    public void run() {
        List<Person> candidates = personMapper.selectCandidatesForProfileGeneration(BATCH_LIMIT);
        log.info("本轮画像生成候选人数: {}", candidates.size());

        for (Person person : candidates) {
            try {
                generateForPerson(person);
            } catch (Exception e) {
                log.error("画像生成失败, personId={}", person.getId(), e);
                // 不中断，继续下一个人，下一轮定时任务会重新尝试
            }
        }
    }

    private void generateForPerson(Person person) {
        T5GenerateProfileRequest request = new T5GenerateProfileRequest();
        request.setTargetId(person.getId().toString());
        request.setTargetType("person");

        T5GenerateProfileResponse response = agentProxyClient.call(
                "T5", "complete_profile", request, T5GenerateProfileResponse.class);

        PersonProfile oldActive = personProfileMapper.selectActiveByPersonId(person.getId());
        int newVersion = (oldActive != null ? oldActive.getPortraitVersion() : 0) + 1;

        PersonProfile newProfile = buildFromResponse(person.getId(), newVersion, response);
        newProfile.setId(UUID.randomUUID());
        newProfile.setStatus("active");
        newProfile.setGeneratedAt(OffsetDateTime.now());
        personProfileMapper.insert(newProfile);

        if (oldActive != null) {
            personProfileMapper.archiveOldActiveVersion(person.getId(), newProfile.getId());
        }
    }

    private PersonProfile buildFromResponse(UUID personId, int version, T5GenerateProfileResponse r) {
        PersonProfile p = new PersonProfile();
        p.setPersonId(personId);
        p.setPortraitVersion(version);
        p.setPoliticalOrientation(r.getPoliticalOrientation());
        p.setPoliticalScore(r.getPoliticalScore());
        p.setPoliticalConfidence(r.getPoliticalConfidence());
        // TODO: emotionProfile/stanceProfile等Object类型字段需序列化为JSON字符串
        p.setPostFrequencyDaily(r.getPostFrequencyDaily());
        p.setContentOriginalRatio(r.getContentOriginalRatio());
        p.setInfluenceScore(r.getInfluenceScore());
        p.setReachScore(r.getReachScore());
        p.setViralityScore(r.getViralityScore());
        p.setMbtiType(r.getMbtiType());
        p.setMbtiConfidence(r.getMbtiConfidence());
        p.setDecisionStyle(r.getDecisionStyle());
        p.setLanguageStyle(r.getLanguageStyle());
        p.setInterestDomains(r.getInterestDomains());
        p.setTargetType(r.getTargetType());
        p.setTargetConfidence(r.getTargetConfidence());
        p.setTargetEvidence(r.getTargetEvidence());
        p.setManipulationRisk(r.getManipulationRisk());
        p.setManipulationScore(r.getManipulationScore());
        return p;
    }
}
