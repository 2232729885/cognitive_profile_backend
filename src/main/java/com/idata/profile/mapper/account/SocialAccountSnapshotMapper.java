package com.idata.profile.mapper.account;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.idata.profile.entity.account.SocialAccountSnapshot;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SocialAccountSnapshotMapper extends BaseMapper<SocialAccountSnapshot> {
    // 只用insert，不提供update/delete方法——快照表只追加，业务上不允许覆盖历史。
}
