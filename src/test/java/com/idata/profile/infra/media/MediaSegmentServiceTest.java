package com.idata.profile.infra.media;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MediaSegmentServiceTest {

    @Test
    void createsDeterministicThirtySecondSegments() {
        MediaSegmentService service = new MediaSegmentService();
        ReflectionTestUtils.setField(service, "ffprobePath", "missing-ffprobe-for-test");
        ReflectionTestUtils.setField(service, "segmentSeconds", 30);
        ReflectionTestUtils.setField(service, "maxSegments", 5);
        ReflectionTestUtils.setField(service, "processTimeoutSeconds", 1);

        List<MediaSegmentService.VideoSegment> segments =
                service.resolveVideoSegments("D:/missing-video-for-test.mp4", 95);

        assertEquals(4, segments.size());
        assertEquals("seg_0", segments.getFirst().segmentId());
        assertEquals(0F, segments.getFirst().start());
        assertEquals(30F, segments.getFirst().end());
        assertEquals("seg_3", segments.getLast().segmentId());
        assertEquals(90F, segments.getLast().start());
        assertEquals(95F, segments.getLast().end());
    }
}
