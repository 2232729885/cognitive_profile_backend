package com.idata.profile.agentproxy.dto.t6;

import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

/** T6目标识别请求。triggerType决定输入是叙事ID还是直接的账号列表。 */
@Data
public class T6IdentifyRequest {
    private String triggerType;      // narrative|account_list|manual
    private UUID narrativeId;
    private UUID[] inputAccountIds;
    private String narrativeLabel;
    private String narrativeFrameType;
    private String narrativeLifecycleState;
    private Integer narrativeContentCount;
    private Integer narrativeAccountCount;
    private BigDecimal narrativeImportanceScore;
    private String narrativeClaimAtoms;
}
