package com.idata.profile.agentproxy.dto.t1;

import lombok.Data;

import java.util.List;

@Data
public class T1AnnotateEventHeatResponse {

    /** 固定为 "t1_annotation_v0.6" */
    private String schemaVersion;

    private EventHeat eventHeat;

    private Double overallConfidence;

    private String processedAt;

    @Data
    public static class EventHeat {
        /** low | medium | high | explosive | unclear */
        private String heatLevel;
        /** 0.0-1.0 */
        private Double heatScore;
        /** content_volume/engagement_surge/rapid_growth/wide_platform_spread/
         *  sustained_attention/declining/insufficient_data/unclear */
        private List<String> heatSignalTypes;
        private String reasoning;
    }
}
