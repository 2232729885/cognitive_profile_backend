package com.idata.profile.ingestion.normalizer;

import com.idata.profile.entity.content.CollectionTask;
import com.idata.profile.entity.raw.RawRecord;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** collection_task 的Step3标准化映射逻辑。最简单的一种类型。 */
@Component
public class CollectionTaskNormalizer {

    public CollectionTask normalize(Object kafkaMessage, RawRecord rawRecord) {
        CollectionTask task = new CollectionTask();
        task.setId(UUID.randomUUID());
        task.setRawRecordId(rawRecord.getId());
        task.setCrawlTaskId(rawRecord.getCrawlTaskId());
        // TODO: 从kafkaMessage.data提取：collectionMethod, seedType, seedValue,
        //   queryExpression, timeWindowStart, timeWindowEnd, targetLanguages,
        //   targetRegions, collectorVersion, recordsCollected

        return task;
    }
}
