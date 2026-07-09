package com.idata.profile.agentproxy;

import com.idata.profile.entity.system.SubAgentRegistry;
import com.idata.profile.mapper.system.SubAgentRegistryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 全系统调用T1-T6 Agent的唯一入口。
 *
 * 设计要点（见 docs/01-CODEGEN-CONTEXT.md 第5节）：
 * mock/真实地址切换只需要 UPDATE sub_agent_registry SET active_url_type='real' WHERE agent_code='T1'，
 * 代码不用改、不用重启。所有调用都必须经过这个类，不要在 pipeline.step 等包里直接拼HTTP请求，
 * 否则某处遗漏会导致mock/real切换不一致。
 *
 * 失败重试：@Retryable 提供基础的网络层重试（连接超时等），
 * 业务层的状态机重试（更新pipeline_tasks.tN_status='failed'，retryCount+1，指数退避）
 * 由调用方（pipeline.step下各Step类）自行处理，两层重试职责不同，不要混淆。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentProxyClient {

    private final SubAgentRegistryMapper subAgentRegistryMapper;
    private final ObjectProvider<AgentProxyClient> selfProvider;

    /**
     * 调用指定Agent的指定action。
     *
     * @param agentCode T1~T6
     * @param action    具体能力名，如 "annotate"（见 sub_agent_registry.actions 字段定义）
     * @param request   请求体，由各 agentproxy.dto.tN 包下DTO序列化
     * @param responseType 响应体类型
     */
    public <T> T call(String agentCode, String action, Object request, Class<T> responseType) {
        if ("T6".equals(agentCode)) {
            return callOnce(agentCode, action, request, responseType);
        }
        return selfProvider.getObject().callWithRetry(agentCode, action, request, responseType);
    }

    @Retryable(retryFor = Exception.class, maxAttempts = 2, backoff = @Backoff(delay = 500))
    public <T> T callWithRetry(String agentCode, String action, Object request, Class<T> responseType) {
        return doCall(agentCode, action, request, responseType);
    }

    public <T> T callOnce(String agentCode, String action, Object request, Class<T> responseType) {
        return doCall(agentCode, action, request, responseType);
    }

    private <T> T doCall(String agentCode, String action, Object request, Class<T> responseType) {
        SubAgentRegistry agent = subAgentRegistryMapper.selectByAgentCode(agentCode);
        if (agent == null || !Boolean.TRUE.equals(agent.getIsActive())) {
            throw new IllegalStateException("Agent未注册或未启用: " + agentCode);
        }

        String baseUrl = "real".equals(agent.getActiveUrlType()) ? agent.getBaseUrl() : agent.getMockUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                String.format("Agent[%s]的%s地址未配置", agentCode, agent.getActiveUrlType()));
        }

        log.debug("调用Agent: code={}, action={}, urlType={}, url={}",
                agentCode, action, agent.getActiveUrlType(), baseUrl);

        return restClient(timeoutSeconds(agentCode, agent.getTimeoutSeconds())).post()
                .uri(baseUrl + "/" + action)
                .body(request)
                .retrieve()
                .body(responseType);
    }

    private RestClient restClient(int timeoutSeconds) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    private int timeoutSeconds(String agentCode, Integer configuredTimeoutSeconds) {
        int timeout = configuredTimeoutSeconds == null || configuredTimeoutSeconds <= 0
                ? 60
                : configuredTimeoutSeconds;
        if ("T6".equals(agentCode)) {
            return Math.max(timeout, 120);
        }
        return timeout;
    }
}
