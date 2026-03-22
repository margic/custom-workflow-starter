package com.anax.kogito.autoconfigure;

import org.kie.kogito.decision.DecisionModels;
import org.kie.kogito.process.impl.DefaultWorkItemHandlerConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;

import java.util.Optional;

/**
 * Spring Boot 3 auto-configuration for Anax Kogito custom work-item handlers.
 *
 * Registers {@code dmn://}, {@code anax://}, and {@code map://} handlers
 * with the Kogito process engine's {@link DefaultWorkItemHandlerConfig}.
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.kie.kogito.process.Process")
@EnableConfigurationProperties(AnaxKogitoProperties.class)
public class AnaxKogitoAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AnaxWorkItemHandler anaxWorkItemHandler(ApplicationContext ctx) {
        return new AnaxWorkItemHandler(ctx);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.kie.kogito.decision.DecisionModels")
    public DmnWorkItemHandler dmnWorkItemHandler(DecisionModels decisionModels) {
        return new DmnWorkItemHandler(decisionModels);
    }

    @Bean
    @ConditionalOnMissingBean
    public MapWorkItemHandler mapWorkItemHandler(ResourceLoader resourceLoader) {
        return new MapWorkItemHandler(resourceLoader);
    }

    @Bean
    @ConditionalOnMissingBean(DefaultWorkItemHandlerConfig.class)
    public DefaultWorkItemHandlerConfig anaxKogitoWorkItemHandlerConfig(
            AnaxWorkItemHandler anaxHandler,
            Optional<DmnWorkItemHandler> dmnHandler,
            MapWorkItemHandler mapHandler) {
        DefaultWorkItemHandlerConfig config = new DefaultWorkItemHandlerConfig();
        config.register("anax", anaxHandler);
        config.register("map", mapHandler);
        dmnHandler.ifPresent(h -> config.register("dmn", h));
        return config;
    }
}
