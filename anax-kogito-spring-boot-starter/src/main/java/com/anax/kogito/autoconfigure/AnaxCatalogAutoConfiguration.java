package com.anax.kogito.autoconfigure;

import com.anax.kogito.catalog.AnaxCatalogController;
import com.anax.kogito.catalog.AnaxCatalogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration for the Anax Catalog feature.
 *
 * Registers:
 * - AnaxCatalogService: Loads catalog.json and scans ApplicationContext
 * - AnaxCatalogController: REST endpoints for catalog access
 *
 * The catalog is enabled by default but can be disabled via:
 *   anax.catalog.enabled=false
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
@EnableConfigurationProperties(AnaxKogitoProperties.class)
public class AnaxCatalogAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = "anax.catalog",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
    )
    public AnaxCatalogService anaxCatalogService(
            ApplicationContext applicationContext,
            ObjectMapper objectMapper) {
        return new AnaxCatalogService(applicationContext, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = "anax.catalog",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
    )
    public AnaxCatalogController anaxCatalogController(AnaxCatalogService catalogService) {
        return new AnaxCatalogController(catalogService);
    }
}
