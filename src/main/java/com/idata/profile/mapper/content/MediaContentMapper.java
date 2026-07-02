package com.idata.profile.mapper.content;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.idata.profile.entity.content.MediaContent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MediaContentMapper extends BaseMapper<MediaContent> {

    @Select("SELECT * FROM media_contents WHERE platform = #{platform} AND platform_content_id = #{platformContentId} LIMIT 1")
    MediaContent selectByPlatformAndContentId(@Param("platform") String platform,
                                              @Param("platformContentId") String platformContentId);
}
