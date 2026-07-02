package com.idata.profile.profile;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProfileReviewRequest {
    private String politicalOrientation;
    private BigDecimal politicalScore;
    private String manipulationRisk;
    private BigDecimal manipulationScore;
    private String targetType;
    private String targetEvidence;
}
