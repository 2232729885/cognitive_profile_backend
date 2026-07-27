package com.idata.profile.service;

import com.idata.profile.entity.account.SocialAccount;
import com.idata.profile.entity.content.MediaContent;
import com.idata.profile.infra.neo4j.Neo4jGraphService;
import com.idata.profile.mapper.account.SocialAccountMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContentMentionSyncService {

    private final SocialAccountMapper socialAccountMapper;
    private final Neo4jGraphService neo4jGraphService;

    public boolean syncMentionedAccountRelations(MediaContent content, String extractionMethod) {
        if (content == null || content.getMentions() == null || content.getMentions().length == 0) {
            return true;
        }
        if (!hasText(content.getPlatform())) {
            return false;
        }

        boolean allSynced = true;
        for (String identifier : mentionIdentifiers(content)) {
            try {
                SocialAccount account = socialAccountMapper.selectMentionedAccount(
                        content.getPlatform(), identifier, "@" + identifier);
                if (account == null || account.getId() == null) {
                    allSynced = false;
                    continue;
                }
                mergeSocialAccountNode(account);
                neo4jGraphService.mergeRelation(
                        "MediaContent", content.getId().toString(),
                        "SocialAccount", account.getId().toString(),
                        "MENTIONS",
                        Map.of("source", "backend_structural",
                                "extraction_method", extractionMethod,
                                "mentionIdentifier", identifier));
            } catch (Exception e) {
                allSynced = false;
                log.warn("Failed to write mentioned account relation to Neo4j, contentId={}, identifier={}",
                        content.getId(), identifier, e);
            }
        }
        return allSynced;
    }

    private Set<String> mentionIdentifiers(MediaContent content) {
        Set<String> identifiers = new LinkedHashSet<>();
        for (String mention : content.getMentions()) {
            String identifier = normalizeMentionIdentifier(mention);
            if (hasText(identifier)) {
                identifiers.add(identifier);
            }
        }
        return identifiers;
    }

    private void mergeSocialAccountNode(SocialAccount account) {
        Map<String, Object> accountProps = new HashMap<>();
        putIfHasText(accountProps, "platform", account.getPlatform());
        putIfHasText(accountProps, "platformUserId", account.getPlatformUserId());
        putIfHasText(accountProps, "handle", account.getHandle());
        putIfHasText(accountProps, "displayName", account.getDisplayName());
        accountProps.put("source", "backend_structural");
        neo4jGraphService.mergeNode("SocialAccount", account.getId().toString(), accountProps);
    }

    private String normalizeMentionIdentifier(String mention) {
        if (!hasText(mention)) {
            return null;
        }
        String identifier = mention.trim();
        while (identifier.startsWith("@")) {
            identifier = identifier.substring(1).trim();
        }
        return identifier;
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
