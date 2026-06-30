package com.idata.profile.mapper.account;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.idata.profile.entity.account.SocialAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.UUID;

@Mapper
public interface SocialAccountMapper extends BaseMapper<SocialAccount> {

    @Select("SELECT * FROM social_accounts WHERE platform = #{platform} AND platform_user_id = #{platformUserId}")
    SocialAccount selectByPlatformAndUserId(@Param("platform") String platform,
                                              @Param("platformUserId") String platformUserId);

    /**
     * UPSERT。MyBatis-Plus默认insert不支持ON CONFLICT，实现见
     * resources/mapper/SocialAccountMapper.xml，用于 social_account 标准化映射场景。
     */
    int upsertByPlatformAndUserId(SocialAccount account);

    @Update("UPDATE social_accounts SET entity_person_id = #{entityPersonId}, updated_at = NOW() WHERE id = #{accountId}")
    int updateEntityPersonId(@Param("accountId") UUID accountId,
                             @Param("entityPersonId") UUID entityPersonId);
}
