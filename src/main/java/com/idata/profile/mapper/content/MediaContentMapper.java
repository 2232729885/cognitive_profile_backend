package com.idata.profile.mapper.content;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.idata.profile.entity.content.MediaContent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MediaContentMapper extends BaseMapper<MediaContent> {
    // 基础CRUD由BaseMapper提供。
    // T1标注字段更新、T2回填authorAccountId等操作直接用 updateById 局部更新即可，
    // 不需要在此额外声明（MyBatis-Plus默认只更新非null字段，配合UpdateWrapper更灵活）。
}
