package com.idata.profile.mapper.graph;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.idata.profile.entity.graph.Organization;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.UUID;

@Mapper
public interface OrganizationMapper extends BaseMapper<Organization> {

    @Insert("""
            WITH updated AS (
                UPDATE organizations
                SET content_count = content_count + 1,
                    importance_score = GREATEST(importance_score, #{importanceScore}),
                    updated_at = NOW()
                WHERE canonical_name = #{canonicalName}
                RETURNING id
            )
            INSERT INTO organizations (
                id, canonical_name, importance_score, is_high_value, content_count
            )
            SELECT gen_random_uuid(), #{canonicalName}, #{importanceScore}, FALSE, 1
            WHERE NOT EXISTS (SELECT 1 FROM updated)
            """)
    int upsertByCanonicalName(@Param("canonicalName") String canonicalName,
                              @Param("importanceScore") BigDecimal importanceScore);

    @Select("SELECT EXISTS(SELECT 1 FROM organizations WHERE id = #{id})")
    boolean existsById(@Param("id") UUID id);

    @Update("""
            UPDATE organizations
            SET merge_history = COALESCE(merge_history, ARRAY[]::uuid[])
                                || #{mergedIds,typeHandler=com.idata.profile.infra.mybatis.UuidArrayTypeHandler},
                updated_at = NOW()
            WHERE id = #{id}
            """)
    int appendMergeHistory(@Param("id") UUID id, @Param("mergedIds") UUID[] mergedIds);
}
