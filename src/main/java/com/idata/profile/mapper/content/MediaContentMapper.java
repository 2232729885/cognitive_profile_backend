package com.idata.profile.mapper.content;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.idata.profile.entity.content.MediaContent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

@Mapper
public interface MediaContentMapper extends BaseMapper<MediaContent> {

    @Select("SELECT * FROM media_contents WHERE platform = #{platform} AND platform_content_id = #{platformContentId} LIMIT 1")
    MediaContent selectByPlatformAndContentId(@Param("platform") String platform,
                                              @Param("platformContentId") String platformContentId);

    /**
     * 查找传播链尚未同步到Neo4j的记录。
     *
     * PostgreSQL migration:
     * ALTER TABLE media_contents ADD COLUMN IF NOT EXISTS
     *     propagation_synced_to_neo4j BOOLEAN NOT NULL DEFAULT FALSE;
     * CREATE INDEX IF NOT EXISTS idx_mc_pending_propagation_sync
     *     ON media_contents(propagation_synced_to_neo4j)
     *     WHERE propagation_synced_to_neo4j = FALSE;
     */
    @Select("SELECT * FROM media_contents WHERE propagation_synced_to_neo4j = FALSE " +
            "AND (parent_content_id IS NOT NULL OR repost_of_content_id IS NOT NULL " +
            "     OR quoted_content_id IS NOT NULL OR root_content_id IS NOT NULL) " +
            "ORDER BY created_at ASC LIMIT #{limit}")
    List<MediaContent> selectPendingPropagationSync(@Param("limit") int limit);

    @Update("UPDATE media_contents SET propagation_synced_to_neo4j = TRUE WHERE id = #{id}")
    int markPropagationSyncedToNeo4j(@Param("id") UUID id);

    @Select("SELECT COUNT(*) FROM media_contents WHERE propagation_synced_to_neo4j = FALSE " +
            "AND (parent_content_id IS NOT NULL OR repost_of_content_id IS NOT NULL " +
            "     OR quoted_content_id IS NOT NULL OR root_content_id IS NOT NULL) " +
            "AND created_at < NOW() - (#{days} || ' days')::INTERVAL")
    long countPendingPropagationOlderThan(@Param("days") int days);
}
