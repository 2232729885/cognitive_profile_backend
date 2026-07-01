package com.idata.profile.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.Arrays;

@Configuration
public class ElasticsearchClientConfig {

    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean(RestClient.class)
    public RestClient elasticsearchRestClient(
            @Value("${elasticsearch.uris}") String uris,
            @Value("${elasticsearch.username:}") String username,
            @Value("${elasticsearch.password:}") String password) {
        HttpHost[] hosts = Arrays.stream(uris.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(HttpHost::create)
                .toArray(HttpHost[]::new);
        RestClientBuilder builder = RestClient.builder(hosts);
        if (StringUtils.hasText(username) && StringUtils.hasText(password)) {
            BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password));
            builder.setHttpClientConfigCallback(httpClientBuilder ->
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider));
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(ElasticsearchTransport.class)
    public ElasticsearchTransport elasticsearchTransport(RestClient elasticsearchRestClient) {
        return new RestClientTransport(elasticsearchRestClient, new JacksonJsonpMapper());
    }

    @Bean
    @ConditionalOnMissingBean(ElasticsearchClient.class)
    public ElasticsearchClient elasticsearchClient(ElasticsearchTransport elasticsearchTransport) {
        return new ElasticsearchClient(elasticsearchTransport);
    }
}
