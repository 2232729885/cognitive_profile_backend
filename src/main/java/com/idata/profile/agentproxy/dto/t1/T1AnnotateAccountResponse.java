package com.idata.profile.agentproxy.dto.t1;

import lombok.Data;

import java.util.List;

@Data
public class T1AnnotateAccountResponse {
    /** Fixed value: "t1_account_annotation_v0.5". */
    private String schemaVersion;

    /** Reuse the account_type structure from T1 annotation v0.5. */
    private T1AnnotateResponse.Annotations.BasicObjective.AccountType accountType;

    private List<T1AnnotateResponse.EvidenceClue> evidenceClues;

    private T1AnnotateResponse.QualityControl qualityControl;

    private Double overallConfidence;

    private String processedAt;
}
