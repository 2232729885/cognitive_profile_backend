package com.idata.profile.config;

import org.neo4j.driver.Driver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.neo4j.core.DatabaseSelectionProvider;
import org.springframework.data.neo4j.core.Neo4jClient;

@Configuration
public class Neo4jClientConfig {

    @Bean
    @ConditionalOnMissingBean(Neo4jClient.class)
    public Neo4jClient neo4jClient(
            Driver driver,
            ObjectProvider<DatabaseSelectionProvider> databaseSelectionProvider) {
        return Neo4jClient.create(
                driver,
                databaseSelectionProvider.getIfAvailable(DatabaseSelectionProvider::getDefaultSelectionProvider));
    }
}
