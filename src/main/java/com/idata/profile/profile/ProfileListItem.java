package com.idata.profile.profile;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class ProfileListItem {
    private UUID personId;
    private String canonicalName;
    private Integer portraitVersion;
    private String status;
    private OffsetDateTime generatedAt;
    private String targetType;
    private String manipulationRisk;
    private BigDecimal manipulationScore;
    private BigDecimal influenceScore;
}
