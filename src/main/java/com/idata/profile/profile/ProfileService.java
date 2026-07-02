package com.idata.profile.profile;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.idata.profile.entity.graph.Person;
import com.idata.profile.entity.profile.PersonProfile;
import com.idata.profile.mapper.graph.PersonMapper;
import com.idata.profile.mapper.profile.PersonProfileMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProfileService {

    private static final int DEFAULT_PAGE = 0;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_HIGH_VALUE_LIMIT = 50;

    private final PersonProfileMapper personProfileMapper;
    private final PersonMapper personMapper;

    public PersonProfile getActiveProfile(UUID personId) {
        return personProfileMapper.selectActiveByPersonId(personId);
    }

    public List<PersonProfile> getProfileHistory(UUID personId) {
        return personProfileMapper.selectList(new QueryWrapper<PersonProfile>()
                .select("id", "person_id", "portrait_version", "status")
                .eq("person_id", personId)
                .orderByDesc("portrait_version"));
    }

    public IPage<ProfileListItem> listProfiles(ProfileListQuery query) {
        ProfileListQuery safeQuery = query == null ? new ProfileListQuery() : query;
        int pageIndex = normalizePage(safeQuery.getPage());
        int pageSize = normalizeSize(safeQuery.getSize(), MAX_PAGE_SIZE);

        QueryWrapper<PersonProfile> wrapper = new QueryWrapper<PersonProfile>()
                .eq("status", "active")
                .eq(hasText(safeQuery.getTargetType()), "target_type", safeQuery.getTargetType())
                .eq(hasText(safeQuery.getManipulationRisk()), "manipulation_risk", safeQuery.getManipulationRisk())
                .orderByDesc("generated_at");

        if (safeQuery.getIsHighValue() != null) {
            List<UUID> personIds = selectPersonIdsByHighValue(safeQuery.getIsHighValue());
            if (personIds.isEmpty()) {
                return emptyPage(pageIndex, pageSize);
            }
            wrapper.in("person_id", personIds);
        }

        Page<PersonProfile> profilePage = personProfileMapper.selectPage(new Page<>(pageIndex + 1L, pageSize), wrapper);
        List<ProfileListItem> items = buildListItems(profilePage.getRecords());

        Page<ProfileListItem> resultPage = new Page<>(pageIndex, pageSize, profilePage.getTotal());
        resultPage.setRecords(items);
        return resultPage;
    }

    public PersonProfile reviewProfile(UUID profileId, ProfileReviewRequest request, UUID reviewerId) {
        ProfileReviewRequest safeRequest = request == null ? new ProfileReviewRequest() : request;
        LambdaUpdateWrapper<PersonProfile> updateWrapper = new LambdaUpdateWrapper<PersonProfile>()
                .eq(PersonProfile::getId, profileId)
                .set(safeRequest.getPoliticalOrientation() != null,
                        PersonProfile::getPoliticalOrientation, safeRequest.getPoliticalOrientation())
                .set(safeRequest.getPoliticalScore() != null,
                        PersonProfile::getPoliticalScore, safeRequest.getPoliticalScore())
                .set(safeRequest.getManipulationRisk() != null,
                        PersonProfile::getManipulationRisk, safeRequest.getManipulationRisk())
                .set(safeRequest.getManipulationScore() != null,
                        PersonProfile::getManipulationScore, safeRequest.getManipulationScore())
                .set(safeRequest.getTargetType() != null,
                        PersonProfile::getTargetType, safeRequest.getTargetType())
                .set(safeRequest.getTargetEvidence() != null,
                        PersonProfile::getTargetEvidence, safeRequest.getTargetEvidence())
                .setSql("reviewed_at = NOW()")
                .set(PersonProfile::getReviewerId, reviewerId);

        int updated = personProfileMapper.update(null, updateWrapper);
        if (updated == 0) {
            return null;
        }
        return personProfileMapper.selectById(profileId);
    }

    public List<ProfileListItem> listHighValueProfiles(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), MAX_HIGH_VALUE_LIMIT);
        List<Person> persons = personMapper.selectList(new QueryWrapper<Person>()
                .eq("is_high_value", true));
        if (persons.isEmpty()) {
            return List.of();
        }

        List<UUID> personIds = persons.stream()
                .map(Person::getId)
                .toList();
        List<PersonProfile> profiles = personProfileMapper.selectList(new QueryWrapper<PersonProfile>()
                .eq("status", "active")
                .in("person_id", personIds));

        Map<UUID, Person> personMap = persons.stream()
                .collect(Collectors.toMap(Person::getId, Function.identity(), (a, b) -> a));
        return profiles.stream()
                .map(profile -> buildListItem(profile, personMap.get(profile.getPersonId())))
                .sorted(this::compareByManipulationScoreDesc)
                .limit(safeLimit)
                .collect(Collectors.toList());
    }

    private List<UUID> selectPersonIdsByHighValue(Boolean isHighValue) {
        return personMapper.selectList(new QueryWrapper<Person>()
                        .select("id")
                        .eq("is_high_value", isHighValue))
                .stream()
                .map(Person::getId)
                .toList();
    }

    private List<ProfileListItem> buildListItems(List<PersonProfile> profiles) {
        if (profiles == null || profiles.isEmpty()) {
            return List.of();
        }
        List<UUID> personIds = profiles.stream()
                .map(PersonProfile::getPersonId)
                .distinct()
                .toList();
        Map<UUID, Person> personMap = personMapper.selectBatchIds(personIds)
                .stream()
                .collect(Collectors.toMap(Person::getId, Function.identity(), (a, b) -> a));

        List<ProfileListItem> items = new ArrayList<>(profiles.size());
        for (PersonProfile profile : profiles) {
            items.add(buildListItem(profile, personMap.get(profile.getPersonId())));
        }
        return items;
    }

    private ProfileListItem buildListItem(PersonProfile profile, Person person) {
        ProfileListItem item = new ProfileListItem();
        item.setPersonId(profile.getPersonId());
        item.setCanonicalName(person == null ? null : person.getCanonicalName());
        item.setPortraitVersion(profile.getPortraitVersion());
        item.setStatus(profile.getStatus());
        item.setGeneratedAt(profile.getGeneratedAt());
        item.setTargetType(profile.getTargetType());
        item.setManipulationRisk(profile.getManipulationRisk());
        item.setManipulationScore(profile.getManipulationScore());
        item.setInfluenceScore(profile.getInfluenceScore());
        return item;
    }

    private IPage<ProfileListItem> emptyPage(int pageIndex, int pageSize) {
        Page<ProfileListItem> page = new Page<>(pageIndex, pageSize, 0);
        page.setRecords(List.of());
        return page;
    }

    private int compareByManipulationScoreDesc(ProfileListItem left, ProfileListItem right) {
        BigDecimal leftScore = left.getManipulationScore();
        BigDecimal rightScore = right.getManipulationScore();
        if (leftScore == null && rightScore == null) {
            return 0;
        }
        if (leftScore == null) {
            return 1;
        }
        if (rightScore == null) {
            return -1;
        }
        return rightScore.compareTo(leftScore);
    }

    private int normalizePage(Integer page) {
        if (page == null || page < DEFAULT_PAGE) {
            return DEFAULT_PAGE;
        }
        return page;
    }

    private int normalizeSize(Integer size, int maxSize) {
        if (size == null || size <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, maxSize);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
