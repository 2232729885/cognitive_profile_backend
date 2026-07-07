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
     * 按 author_account_id 批量查帖子，供 T6 识别时使用。
     * limit 限制最多返回条数，避免请求体过大。
     */
    @Select("<script>" +
            "SELECT * FROM media_contents " +
            "WHERE author_account_id IN " +
            "<foreach collection='accountIds' item='id' open='(' separator=',' close=')'>" +
            "#{id}" +
            "</foreach>" +
            " ORDER BY published_at DESC" +
            " LIMIT #{limit}" +
            "</script>")
    List<MediaContent> selectByAuthorAccountIds(
            @Param("accountIds") List<UUID> accountIds,
            @Param("limit") int limit);

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

    @Update("UPDATE media_contents " +
            "SET media_asset_ids = array_append(coalesce(media_asset_ids, ARRAY[]::uuid[]), #{assetId}::uuid) " +
            "WHERE id = #{contentId} " +
            "AND (media_asset_ids IS NULL OR NOT (#{assetId}::uuid = ANY(media_asset_ids)))")
    int appendMediaAssetId(@Param("contentId") UUID contentId,
                           @Param("assetId") String assetId);

    @Update("""
            UPDATE media_contents mc
            SET media_asset_ids = subq.asset_ids
            FROM (
                SELECT content_id,
                       array_agg(id ORDER BY created_at) AS asset_ids
                FROM media_assets
                WHERE content_id IS NOT NULL
                GROUP BY content_id
            ) subq
            WHERE mc.id = subq.content_id
            """)
    int backfillMediaAssetIds();

    @Select("SELECT COUNT(*) FROM media_contents WHERE propagation_synced_to_neo4j = FALSE " +
            "AND (parent_content_id IS NOT NULL OR repost_of_content_id IS NOT NULL " +
            "     OR quoted_content_id IS NOT NULL OR root_content_id IS NOT NULL) " +
            "AND created_at < NOW() - (#{days} || ' days')::INTERVAL")
    long countPendingPropagationOlderThan(@Param("days") int days);
}
