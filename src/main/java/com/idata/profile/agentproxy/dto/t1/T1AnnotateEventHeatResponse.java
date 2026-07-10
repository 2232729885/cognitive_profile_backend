package com.idata.profile.agentproxy.dto.t1;

import lombok.Data;

import java.util.List;

@Data
public class T1AnnotateEventHeatResponse {
    /** Fixed value: t1_event_heat_v1. */
    private String schemaVersion;
    private String eventId;
    private EventHeat eventHeat;
    private Double overallConfidence;
    private String processedAt;

    @Data
    public static class EventHeat {
        /** low | medium | high | explosive | unclear */
        private String heatLevel;
        private Double heatScore;
        /** content_volume/engagement_surge/rapid_growth/wide_platform_spread/
         *  sustained_attention/declining/insufficient_data/unclear */
        private List<String> heatSignalTypes;
        private String reasoning;
        private Double confidence;
    }
}
