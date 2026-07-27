package com.idata.profile.infra.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MediaAsrServiceTest {

    @Test
    void missingAudioIsAnEmptyResultInsteadOfFailure() {
        MediaAsrService.TranscriptionResult result = new MediaAsrService().transcribeResult(null);

        assertEquals(MediaAsrService.TranscriptionStatus.EMPTY, result.status());
        assertNull(result.text());
        assertNull(result.error());
    }
}
