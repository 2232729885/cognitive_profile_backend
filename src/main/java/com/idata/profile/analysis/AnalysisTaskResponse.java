package com.idata.profile.analysis;

import java.util.UUID;

public record AnalysisTaskResponse(
        UUID taskId,
        UUID sessionId,
        String status,
        String streamUrl
) {
}
