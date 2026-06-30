package com.idata.profile.config;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.StringUtils;

@Configuration
public class MilvusClientConfig {

    @Bean(destroyMethod = "close")
    @Lazy
    @ConditionalOnMissingBean(MilvusClientV2.class)
    public MilvusClientV2 milvusClientV2(
            @Value("${milvus.uri:}") String uri,
            @Value("${milvus.host}") String host,
            @Value("${milvus.port}") int port,
            @Value("${milvus.token:}") String token,
            @Value("${milvus.username:}") String username,
            @Value("${milvus.password:}") String password,
            @Value("${milvus.db-name:}") String dbName) {
        ConnectConfig.ConnectConfigBuilder builder = ConnectConfig.builder()
                .uri(resolveUri(uri, host, port));

        if (StringUtils.hasText(token)) {
            builder.token(token);
        }
        if (StringUtils.hasText(username)) {
            builder.username(username);
        }
        if (StringUtils.hasText(password)) {
            builder.password(password);
        }
        if (StringUtils.hasText(dbName)) {
            builder.dbName(dbName);
        }

        return new MilvusClientV2(builder.build());
    }

    private String resolveUri(String uri, String host, int port) {
        if (StringUtils.hasText(uri)) {
            return uri;
        }
        return "http://" + host + ":" + port;
    }
}
