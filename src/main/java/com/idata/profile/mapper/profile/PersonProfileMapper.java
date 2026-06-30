package com.idata.profile.mapper.profile;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.idata.profile.entity.profile.PersonProfile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.UUID;

@Mapper
public interface PersonProfileMapper extends BaseMapper<PersonProfile> {

    @Select("SELECT * FROM person_profiles WHERE person_id = #{personId} AND status = 'active'")
    PersonProfile selectActiveByPersonId(@Param("personId") UUID personId);

    /** 画像生成Step：新版本写入后，把旧的active版本归档 */
    @Update("UPDATE person_profiles SET status = 'archived' " +
            "WHERE person_id = #{personId} AND status = 'active' AND id != #{excludeId}")
    int archiveOldActiveVersion(@Param("personId") UUID personId, @Param("excludeId") UUID excludeId);
}
