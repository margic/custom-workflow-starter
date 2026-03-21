package com.anax.kogito.autoconfigure;

import org.kie.kogito.decision.DecisionModels;
import org.kie.kogito.process.impl.DefaultWorkItemHandlerConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import java.util.Optional;

/**
 * Spring Boot auto-configuration for Anax Kogito custom URI scheme support.
 *
 * Registers work-item handlers for dmn://, anax://, and map:// URI schemes.
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.kie.kogito.process.Process")
@EnableConfigurationProperties(AnaxKogitoProperties.class)
public class AnaxKogitoAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AnaxWorkItemHandler anaxWorkItemHandler(ApplicationContext applicationContext) {
        return new AnaxWorkItemHandler(applicationContext);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.kie.kogito.decision.DecisionModels")
    public DmnWorkItemHandler dmnWorkItemHandler(DecisionModels decisionModels) {
        return new DmnWorkItemHandler(decisionModels);
    }

    @Bean
    @ConditionalOnMissingBean
    public MapWorkItemHandler mapWorkItemHandler(ApplicationContext applicationContext) {
        return new MapWorkItemHandler(applicationContext);
    }

    @Bean
    @ConditionalOnMissingBean(name = "workItemHandlerConfig")
    public DefaultWorkItemHandlerConfig anaxKogitoWorkItemHandlerConfig(
            AnaxWorkItemHandler anaxHandler,
            Optional<DmnWorkItemHandler> dmnHandler,
            MapWorkItemHandler mapHandler) {

        DefaultWorkItemHandlerConfig config = new DefaultWorkItemHandlerConfig();
        config.register("anax", anaxHandler);
        config.register("map", mapHandler);
        dmnHandler.ifPresent(handler -> config.register("dmn", handler));

        return config;
    }
}
