package com.idata.profile.mapper.content;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.idata.profile.entity.content.MediaAsset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MediaAssetMapper extends BaseMapper<MediaAsset> {

    /**
     * sha256去重写入。注意：MyBatis-Plus的insert()不直接支持ON CONFLICT，
     * 需要在XML或@Insert中手写，这里给出方法签名，实现见 resources/mapper/MediaAssetMapper.xml
     */
    int insertIgnoreOnConflictSha256(MediaAsset asset);

    /** T4异步图像向量化任务用：查还没生成向量的图片/视频，见 batch.asset.ImageEmbeddingJob */
    @Select("SELECT * FROM media_assets WHERE embedding_id IS NULL " +
            "AND asset_type IN ('image','video') LIMIT #{limit}")
    List<MediaAsset> selectPendingEmbedding(@Param("limit") int limit);
}
