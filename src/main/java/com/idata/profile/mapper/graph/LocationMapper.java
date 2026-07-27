package com.idata.profile.mapper.graph;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.idata.profile.entity.graph.Location;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Mapper
public interface LocationMapper extends BaseMapper<Location> {

    @Insert("""
            INSERT INTO locations (
                id, canonical_name, aliases, location_type, country,
                importance_score, content_count, dedup_status
            )
            VALUES (
                #{id}, #{canonicalName},
                #{aliases,typeHandler=com.idata.profile.infra.mybatis.StringArrayTypeHandler},
                #{locationType}, #{country}, #{importanceScore}, 1, 'pending'
            )
            ON CONFLICT (id) DO UPDATE SET
                aliases = array(
                    SELECT DISTINCT alias FROM unnest(
                        COALESCE(locations.aliases, ARRAY[]::TEXT[])
                        || COALESCE(EXCLUDED.aliases, ARRAY[]::TEXT[])
                    ) AS alias
                    WHERE alias IS NOT NULL AND btrim(alias) <> ''
                ),
                location_type = COALESCE(EXCLUDED.location_type, locations.location_type),
                country = COALESCE(EXCLUDED.country, locations.country),
                importance_score = GREATEST(locations.importance_score, EXCLUDED.importance_score),
                content_count = locations.content_count + 1,
                updated_at = NOW()
            """)
    int insertEntity(@Param("id") UUID id,
                     @Param("canonicalName") String canonicalName,
                     @Param("aliases") String[] aliases,
                     @Param("locationType") String locationType,
                     @Param("country") String country,
                     @Param("importanceScore") BigDecimal importanceScore);

    @Select("SELECT COUNT(*) FROM locations WHERE dedup_status = #{dedupStatus}")
    long countByDedupStatus(@Param("dedupStatus") String dedupStatus);

    @Select("SELECT * FROM locations WHERE dedup_status = #{status} " +
            "ORDER BY created_at ASC LIMIT #{limit}")
    List<Location> selectByDedupStatus(@Param("status") String status, @Param("limit") int limit);

    @Select("SELECT * FROM locations WHERE canonical_name = #{canonicalName} " +
            "AND dedup_status = 'pending' ORDER BY created_at ASC")
    List<Location> selectPendingByCanonicalName(@Param("canonicalName") String canonicalName);

    @Select("SELECT canonical_name FROM locations WHERE dedup_status = 'pending' " +
            "GROUP BY canonical_name HAVING COUNT(*) > 1 LIMIT #{limit}")
    List<String> selectDuplicateCanonicalNames(@Param("limit") int limit);

    @Update("""
            UPDATE locations SET
                content_count = #{contentCount},
                merge_history = COALESCE(merge_history, ARRAY[]::uuid[])
                                || #{mergedIds,typeHandler=com.idata.profile.infra.mybatis.UuidArrayTypeHandler},
                dedup_status = 'canonical',
                updated_at = NOW()
            WHERE id = #{survivorId}
            """)
    int updateSurvivorAfterMerge(@Param("survivorId") UUID survivorId,
                                 @Param("contentCount") int contentCount,
                                 @Param("mergedIds") UUID[] mergedIds);

    @Select("SELECT dedup_status AS status, COUNT(*) AS cnt FROM locations GROUP BY dedup_status")
    List<Map<String, Object>> selectDedupStatusStats();

    @Select("SELECT EXISTS(SELECT 1 FROM locations WHERE id = #{id})")
    boolean existsById(@Param("id") UUID id);

    @Select("SELECT id FROM locations WHERE canonical_name = #{canonicalName} LIMIT 1")
    UUID selectIdByCanonicalName(@Param("canonicalName") String canonicalName);

    @Select("SELECT id FROM locations WHERE canonical_name = #{name} " +
            "ORDER BY CASE dedup_status WHEN 'canonical' THEN 0 WHEN 'pending' THEN 1 ELSE 2 END, " +
            "created_at ASC LIMIT 1")
    UUID selectCanonicalIdByName(@Param("name") String name);

    @Update("""
            UPDATE locations
            SET merge_history = COALESCE(merge_history, ARRAY[]::uuid[])
                                || #{mergedIds,typeHandler=com.idata.profile.infra.mybatis.UuidArrayTypeHandler},
                updated_at = NOW()
            WHERE id = #{id}
            """)
    int appendMergeHistory(@Param("id") UUID id, @Param("mergedIds") UUID[] mergedIds);
}
