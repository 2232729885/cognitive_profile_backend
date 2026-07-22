package com.idata.profile.mapper.content;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.idata.profile.entity.content.MediaAsset;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

@Mapper
public interface MediaAssetMapper extends BaseMapper<MediaAsset> {

    @Insert("""
            INSERT INTO media_assets (
                id, raw_record_id, content_id, source_asset_id, asset_type,
                source_url, storage_uri, mime_type, sha256, file_size_bytes,
                width, height, duration_seconds, thumbnail_uri, ocr_text, asr_text, caption_text,
                translated_ocr_text, translated_asr_text, translated_caption_text,
                aigc_score, minio_bucket, minio_key, embedding_id
            )
            VALUES (
                #{asset.id}, #{asset.rawRecordId}, #{asset.contentId}, #{asset.sourceAssetId}, #{asset.assetType},
                #{asset.sourceUrl}, #{asset.storageUri}, #{asset.mimeType}, #{asset.sha256}, #{asset.fileSizeBytes},
                #{asset.width}, #{asset.height}, #{asset.durationSeconds}, #{asset.thumbnailUri}, #{asset.ocrText}, #{asset.asrText}, #{asset.captionText},
                #{asset.translatedOcrText}, #{asset.translatedAsrText}, #{asset.translatedCaptionText},
                #{asset.aigcScore}, #{asset.minioBucket}, #{asset.minioKey}, #{asset.embeddingId}
            )
            ON CONFLICT (sha256) WHERE sha256 IS NOT NULL DO NOTHING
            """)
    int insertIgnoreOnConflictSha256(@Param("asset") MediaAsset asset);

    @Select("SELECT * FROM media_assets WHERE sha256 = #{sha256} LIMIT 1")
    MediaAsset selectBySha256(@Param("sha256") String sha256);

    @Select("SELECT * FROM media_assets WHERE embedding_id IS NULL " +
            "AND asset_type IN ('image','video','audio') LIMIT #{limit}")
    List<MediaAsset> selectPendingEmbedding(@Param("limit") int limit);

    @Select("SELECT * FROM media_assets WHERE ocr_text IS NULL " +
            "AND asset_type = 'image' ORDER BY created_at ASC LIMIT #{limit}")
    List<MediaAsset> selectPendingOcr(@Param("limit") int limit);

    @Select("SELECT * FROM media_assets WHERE " +
            "((ocr_text IS NOT NULL AND translated_ocr_text IS NULL) " +
            "OR (asr_text IS NOT NULL AND translated_asr_text IS NULL) " +
            "OR (caption_text IS NOT NULL AND translated_caption_text IS NULL)) " +
            "AND asset_type IN ('image','video','audio') AND content_id IS NOT NULL " +
            "ORDER BY created_at ASC LIMIT #{limit}")
    List<MediaAsset> selectImageAssetsWithOcrText(@Param("limit") int limit);

    @Select("SELECT * FROM media_assets WHERE caption_text IS NULL " +
            "AND asset_type = 'image' " +
            "AND (source_url IS NOT NULL OR (minio_bucket IS NOT NULL AND minio_key IS NOT NULL)) " +
            "ORDER BY created_at ASC LIMIT #{limit}")
    List<MediaAsset> selectPendingCaption(@Param("limit") int limit);

    /** 查找有资产待T1标注、且资产已关联内容的内容ID列表（去重，用于ImageEmbeddingJob按内容重新标注） */
    @Select("SELECT DISTINCT content_id FROM media_assets " +
            "WHERE t1_annotated = FALSE AND content_id IS NOT NULL " +
            "ORDER BY content_id LIMIT #{limit}")
    List<UUID> selectContentIdsPendingT1Annotation(@Param("limit") int limit);

    @Select("SELECT * FROM media_assets WHERE content_id = #{contentId} " +
            "AND asset_type IN ('image','video') ORDER BY created_at ASC")
    List<MediaAsset> selectByContentId(@Param("contentId") UUID contentId);

    @Select("""
            <script>
            SELECT * FROM media_assets WHERE source_asset_id IN
            <foreach item='id' collection='assetIds' open='(' separator=',' close=')'>#{id}</foreach>
            AND content_id IS NULL
            </script>
            """)
    List<MediaAsset> selectUnlinkedBySourceAssetIds(@Param("assetIds") List<String> assetIds);

    @Update("UPDATE media_assets SET t1_annotated = TRUE WHERE id = #{id}")
    int markT1Annotated(@Param("id") UUID id);

    @Select("""
            <script>
            SELECT * FROM media_assets WHERE id IN
            <foreach collection='ids' item='id' open='(' separator=',' close=')'>
                #{id,typeHandler=com.idata.profile.infra.mybatis.UuidTypeHandler}
            </foreach>
            </script>
            """)
    List<MediaAsset> selectByIds(@Param("ids") List<UUID> ids);
}
