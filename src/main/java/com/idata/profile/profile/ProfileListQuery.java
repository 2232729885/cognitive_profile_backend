package com.idata.profile.profile;

import lombok.Data;

@Data
public class ProfileListQuery {
    private String targetType;
    private String manipulationRisk;
    private Boolean isHighValue;
    private Integer page = 0;
    private Integer size = 20;
}
