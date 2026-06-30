package com.idata.profile.mapper.system;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.idata.profile.entity.system.SubAgentRegistry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SubAgentRegistryMapper extends BaseMapper<SubAgentRegistry> {

    /** agentproxy.AgentProxyClient 每次调用前查询，决定走mockUrl还是baseUrl */
    @Select("SELECT * FROM sub_agent_registry WHERE agent_code = #{agentCode}")
    SubAgentRegistry selectByAgentCode(@Param("agentCode") String agentCode);
}
