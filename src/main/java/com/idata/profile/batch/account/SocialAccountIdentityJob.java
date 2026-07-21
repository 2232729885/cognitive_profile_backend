package com.idata.profile.batch.account;

import com.idata.profile.agentproxy.dto.t2.T2ExtractResponse;
import com.idata.profile.entity.account.SocialAccount;
import com.idata.profile.infra.neo4j.Neo4jGraphService;
import com.idata.profile.mapper.account.SocialAccountMapper;
import com.idata.profile.service.EntityResolutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class SocialAccountIdentityJob {

    private static final int BATCH_LIMIT = 200;

    private static final Set<String> PERSON_ACCOUNT_TYPES = Set.of(
            "ordinary_user", "political_actor", "academic_or_expert", "influencer_kol");

    private static final Set<String> ORGANIZATION_ACCOUNT_TYPES = Set.of(
            "news_media", "state_affiliated_media", "government_agency", "political_party_or_campaign",
            "military_security_agency", "international_organization", "ngo_or_civil_society",
            "commercial_brand", "platform_official", "community_group");

    private final SocialAccountMapper socialAccountMapper;
    private final EntityResolutionService entityResolutionService;
    private final Neo4jGraphService neo4jGraphService;

    @Scheduled(fixedDelay = 15 * 60 * 1000)
    public void run() {
        List<SocialAccount> pending = socialAccountMapper.selectPendingIdentityResolution(BATCH_LIMIT);
        if (pending.isEmpty()) {
            return;
        }

        log.info("[SocialAccountIdentityJob] identity resolution backfill, count={}", pending.size());
        int resolved = 0;
        for (SocialAccount account : pending) {
            try {
                processAccount(account);
                resolved++;
            } catch (Exception e) {
                log.warn("[SocialAccountIdentityJob] failed, accountId={}", account.getId(), e);
            }
        }
        log.info("[SocialAccountIdentityJob] completed, resolved={}/{}", resolved, pending.size());
    }

    private void processAccount(SocialAccount account) {
        String targetType = resolveTargetType(account.getAccountType());
        if (targetType == null) {
            socialAccountMapper.markIdentityResolved(account.getId());
            return;
        }

        String name = hasText(account.getDisplayName()) ? account.getDisplayName() : account.getHandle();
        if (!hasText(name)) {
            socialAccountMapper.markIdentityResolved(account.getId());
            return;
        }

        T2ExtractResponse.ExtractedMention mention = new T2ExtractResponse.ExtractedMention();
        mention.setMentionId(account.getId().toString());
        mention.setName(name);
        mention.setCanonicalName(name);
        mention.setType(targetType);
        mention.setAliases(List.of());
        Map<String, Object> attributes = new HashMap<>();
        if (hasText(account.getBio())) {
            attributes.put("bio", account.getBio());
        }
        mention.setAttributes(attributes);

        EntityResolutionService.ResolutionResult resolutionResult = entityResolutionService.resolveMentions(
                List.of(mention), account.getId().toString(), account.getPlatform(), null, accountContextText(account));
        Map<String, EntityResolutionService.ResolvedMention> resolvedMap = resolutionResult.getResolvedMentions();
        EntityResolutionService.ResolvedMention resolvedMention = resolvedMap.get(account.getId().toString());

        if (resolvedMention != null && resolvedMention.getNodeId() != null) {
            UUID entityId;
            try {
                entityId = UUID.fromString(resolvedMention.getNodeId());
            } catch (IllegalArgumentException e) {
                entityId = null;
            }
            if (entityId != null) {
                if ("person".equals(targetType)) {
                    socialAccountMapper.updateEntityPersonId(account.getId(), entityId);
                } else {
                    socialAccountMapper.updateEntityOrgId(account.getId(), entityId);
                }
            }
            mergeSocialAccountNode(account);
            neo4jGraphService.mergeRelation(
                    resolvedMention.getLabel(), resolvedMention.getNodeId(),
                    "SocialAccount", account.getId().toString(),
                    "HAS_ACCOUNT",
                    Map.of("confidence", 0.9D, "source", "social_account_identity_job",
                            "extraction_method", "display_name_resolution"));
        }

        socialAccountMapper.markIdentityResolved(account.getId());
    }

    private String resolveTargetType(String accountType) {
        if (accountType == null) {
            return null;
        }
        if (PERSON_ACCOUNT_TYPES.contains(accountType)) {
            return "person";
        }
        if (ORGANIZATION_ACCOUNT_TYPES.contains(accountType)) {
            return "organization";
        }
        return null;
    }

    private String accountContextText(SocialAccount account) {
        StringBuilder text = new StringBuilder();
        appendIfHasText(text, account.getDisplayName());
        appendIfHasText(text, account.getHandle());
        appendIfHasText(text, account.getBio());
        appendIfHasText(text, account.getSelfDeclaredLocation());
        return text.toString().trim();
    }

    private void appendIfHasText(StringBuilder text, String value) {
        if (!hasText(value)) {
            return;
        }
        if (!text.isEmpty()) {
            text.append('\n');
        }
        text.append(value.trim());
    }

    private void mergeSocialAccountNode(SocialAccount account) {
        Map<String, Object> props = new HashMap<>();
        putIfHasText(props, "platform", account.getPlatform());
        putIfHasText(props, "platformUserId", account.getPlatformUserId());
        putIfHasText(props, "handle", account.getHandle());
        putIfHasText(props, "displayName", account.getDisplayName());
        props.put("source", "backend_structural");
        neo4jGraphService.mergeNode("SocialAccount", account.getId().toString(), props);
    }

    private void putIfHasText(Map<String, Object> target, String key, String value) {
        if (hasText(value)) {
            target.put(key, value);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
