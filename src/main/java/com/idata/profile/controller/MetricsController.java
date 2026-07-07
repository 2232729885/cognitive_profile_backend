package com.idata.profile.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.idata.profile.common.response.Result;
import com.idata.profile.entity.account.SocialAccount;
import com.idata.profile.entity.content.MediaContent;
import com.idata.profile.entity.graph.Narrative;
import com.idata.profile.entity.graph.Person;
import com.idata.profile.entity.profile.PersonProfile;
import com.idata.profile.entity.raw.RawRecord;
import com.idata.profile.infra.kafka.KafkaMonitorService;
import com.idata.profile.infra.neo4j.Neo4jGraphService;
import com.idata.profile.mapper.account.SocialAccountMapper;
import com.idata.profile.mapper.content.MediaContentMapper;
import com.idata.profile.mapper.graph.NarrativeMapper;
import com.idata.profile.mapper.graph.PersonMapper;
import com.idata.profile.mapper.profile.PersonProfileMapper;
import com.idata.profile.mapper.raw.RawRecordMapper;
import com.idata.profile.mapper.task.PipelineTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

@RestController
@RequestMapping("/api/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final PipelineTaskMapper pipelineTaskMapper;
    private final PersonProfileMapper personProfileMapper;
    private final PersonMapper personMapper;
    private final Neo4jGraphService neo4jGraphService;
    private final RawRecordMapper rawRecordMapper;
    private final MediaContentMapper mediaContentMapper;
    private final SocialAccountMapper socialAccountMapper;
    private final NarrativeMapper narrativeMapper;
    private final ExecutorService pipelineThreadPool;
    private final KafkaMonitorService kafkaMonitorService;

    @GetMapping("/pipeline")
    public Result<Map<String, Object>> pipeline(@RequestParam(defaultValue = "24") int hours) {
        return Result.ok(pipelineStats(normalizeHours(hours)));
    }

    @GetMapping("/data-volume")
    public Result<Map<String, Object>> dataVolume() {
        return Result.ok(dataVolumeStats());
    }

    @GetMapping("/neo4j")
    public Result<Map<String, Object>> neo4j() {
        return Result.ok(neo4jGraphService.getGraphStats());
    }

    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        CompletableFuture<Long> mediaContentsFuture = supply(() -> mediaContentMapper.selectCount(null));
        CompletableFuture<Long> socialAccountsFuture = supply(() -> socialAccountMapper.selectCount(null));
        CompletableFuture<Long> profilesFuture = supply(() -> personProfileMapper.selectCount(null));
        CompletableFuture<Map<String, Object>> graphStatsFuture = supply(neo4jGraphService::getGraphStats);
        CompletableFuture<Map<String, Object>> rawStatsFuture = supply(this::rawPipelineOverview);

        CompletableFuture.allOf(mediaContentsFuture, socialAccountsFuture, profilesFuture,
                graphStatsFuture, rawStatsFuture).join();

        Map<String, Object> graphStats = graphStatsFuture.join();
        Map<String, Object> rawStats = rawStatsFuture.join();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalContents", mediaContentsFuture.join());
        result.put("totalAccounts", socialAccountsFuture.join());
        result.put("totalProfiles", profilesFuture.join());
        result.put("graphNodeCount", graphStats.get("nodeCount"));
        result.put("t4IndexedSuccessRate", rawStats.get("t4IndexedSuccessRate"));
        result.put("last24hCount", rawStats.get("last24hCount"));
        return Result.ok(result);
    }

    @GetMapping("/kafka")
    public Result<List<Map<String, Object>>> kafka() {
        return Result.ok(kafkaMonitorService.getTopicStats());
    }

    private Map<String, Object> pipelineStats(int hours) {
        Map<String, Object> stats = new LinkedHashMap<>(pipelineTaskMapper.selectPerformanceStats(hours));
        stats.put("queryHours", hours);
        return stats;
    }

    private Map<String, Object> dataVolumeStats() {
        CompletableFuture<Map<String, Object>> rawRecordsFuture = supply(this::rawRecordStats);
        CompletableFuture<Long> mediaContentsFuture = supply(() -> mediaContentMapper.selectCount(null));
        CompletableFuture<Long> socialAccountsFuture = supply(() -> socialAccountMapper.selectCount(null));
        CompletableFuture<Long> personsFuture = supply(() -> personMapper.selectCount(null));
        CompletableFuture<Map<String, Object>> personProfilesFuture = supply(this::personProfileStats);
        CompletableFuture<Long> narrativesFuture = supply(() -> narrativeMapper.selectCount(null));

        CompletableFuture.allOf(rawRecordsFuture, mediaContentsFuture, socialAccountsFuture,
                personsFuture, personProfilesFuture, narrativesFuture).join();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rawRecords", rawRecordsFuture.join());
        result.put("mediaContents", mediaContentsFuture.join());
        result.put("socialAccounts", socialAccountsFuture.join());
        result.put("persons", personsFuture.join());
        result.put("personProfiles", personProfilesFuture.join());
        result.put("narratives", narrativesFuture.join());
        return result;
    }

    private Map<String, Object> rawRecordStats() {
        List<Map<String, Object>> rows = rawRecordMapper.selectMaps(
                new QueryWrapper<RawRecord>()
                        .select("record_type", "COUNT(*) AS cnt")
                        .groupBy("record_type"));
        Map<String, Object> byType = toCountMap(rows, "record_type");
        long total = byType.values().stream().mapToLong(this::longValue).sum();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("byType", byType);
        return result;
    }

    private Map<String, Object> personProfileStats() {
        List<Map<String, Object>> rows = personProfileMapper.selectMaps(
                new QueryWrapper<PersonProfile>()
                        .select("status", "COUNT(*) AS cnt")
                        .groupBy("status"));
        return toCountMap(rows, "status");
    }

    private Map<String, Object> rawPipelineOverview() {
        Long total = rawRecordMapper.selectCount(null);
        Long indexed = rawRecordMapper.selectCount(
                new QueryWrapper<RawRecord>().eq("pipeline_status", "T4_INDEXED"));
        Long last24h = rawRecordMapper.selectCount(
                new QueryWrapper<RawRecord>().gt("created_at", OffsetDateTime.now().minusHours(24)));

        double successRate = total == null || total == 0 ? 0D : indexed.doubleValue() / total.doubleValue();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("t4IndexedSuccessRate", successRate);
        result.put("last24hCount", last24h == null ? 0L : last24h);
        return result;
    }

    private Map<String, Object> toCountMap(List<Map<String, Object>> rows, String keyColumn) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            result.put(stringValue(row.get(keyColumn)), longValue(row.get("cnt")));
        }
        return result;
    }

    private int normalizeHours(int hours) {
        if (hours <= 0) {
            return 24;
        }
        return Math.min(hours, 168);
    }

    private <T> CompletableFuture<T> supply(java.util.function.Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, pipelineThreadPool);
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        return Long.parseLong(value.toString());
    }
}
