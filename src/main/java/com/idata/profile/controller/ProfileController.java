package com.idata.profile.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.idata.profile.common.response.Result;
import com.idata.profile.entity.profile.PersonProfile;
import com.idata.profile.infra.neo4j.Neo4jGraphService;
import com.idata.profile.profile.ProfileListItem;
import com.idata.profile.profile.ProfileListQuery;
import com.idata.profile.profile.ProfileReviewRequest;
import com.idata.profile.profile.ProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/profiles")
@RequiredArgsConstructor
public class ProfileController {

    private static final String PROFILE_NOT_FOUND = "\u753b\u50cf\u4e0d\u5b58\u5728";
    private static final String INVALID_USER_ID = "X-User-Id\u8bf7\u6c42\u5934\u4e0d\u80fd\u4e3a\u7a7a\u6216\u683c\u5f0f\u9519\u8bef";

    private final ProfileService profileService;
    private final Neo4jGraphService neo4jGraphService;

    @GetMapping("/persons/{personId}")
    public Result<PersonProfile> getActiveProfile(@PathVariable UUID personId) {
        PersonProfile profile = profileService.getActiveProfile(personId);
        if (profile == null) {
            return Result.fail("NOT_FOUND", PROFILE_NOT_FOUND);
        }
        return Result.ok(profile);
    }

    @GetMapping("/persons/{personId}/history")
    public Result<List<PersonProfile>> getProfileHistory(@PathVariable UUID personId) {
        return Result.ok(profileService.getProfileHistory(personId));
    }

    @PostMapping("/list")
    public Result<IPage<ProfileListItem>> listProfiles(@RequestBody(required = false) ProfileListQuery query) {
        return Result.ok(profileService.listProfiles(query));
    }

    @PutMapping("/{profileId}/review")
    public Result<PersonProfile> reviewProfile(@PathVariable UUID profileId,
                                               @RequestBody(required = false) ProfileReviewRequest request,
                                               @RequestHeader(value = "X-User-Id", required = false) String userId) {
        UUID reviewerId = parseReviewerId(userId);
        if (reviewerId == null) {
            return Result.fail("BAD_REQUEST", INVALID_USER_ID);
        }

        PersonProfile profile = profileService.reviewProfile(profileId, request, reviewerId);
        if (profile == null) {
            return Result.fail("NOT_FOUND", PROFILE_NOT_FOUND);
        }
        return Result.ok(profile);
    }

    @GetMapping("/high-value")
    public Result<List<ProfileListItem>> listHighValueProfiles(@RequestParam(defaultValue = "20") int limit) {
        return Result.ok(profileService.listHighValueProfiles(limit));
    }

    @GetMapping("/persons/{personId}/graph")
    public Result<Map<String, Object>> findPersonGraph(@PathVariable UUID personId) {
        return Result.ok(neo4jGraphService.findTwoHopGraph(personId.toString(), "Person"));
    }

    private UUID parseReviewerId(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(userId.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
