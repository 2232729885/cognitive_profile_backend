package com.idata.profile.mapper.session;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.idata.profile.entity.session.SessionMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

@Mapper
public interface SessionMessageMapper extends BaseMapper<SessionMessage> {

    @Select("SELECT * FROM session_messages WHERE session_id = #{sessionId} ORDER BY created_at ASC")
    List<SessionMessage> selectBySessionIdOrderByCreatedAt(@Param("sessionId") UUID sessionId);
}
