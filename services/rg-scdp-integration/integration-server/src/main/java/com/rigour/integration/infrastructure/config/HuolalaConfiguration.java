package com.rigour.integration.infrastructure.config;

import com.rigour.integration.application.service.huolala.HuolalaService.DeploymentSettings;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
public class HuolalaConfiguration {
    @Bean
    public DeploymentSettings huolalaDeploymentSettings(Environment environment) {
        return new DeploymentSettings(
                environment.getProperty("rigour.huolala.enterprise-id"),
                environment.getProperty("rigour.huolala.enterprise-name"),
                environment.getProperty("rigour.huolala.secret-ref"),
                environment.getProperty("rigour.huolala.redirect-uri"),
                environment.getProperty("rigour.huolala.sandbox", Boolean.class));
    }

    @Bean(name = "huolalaExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor huolalaExecutor() {
        var pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(1);
        pool.setMaxPoolSize(2);
        pool.setQueueCapacity(10);
        pool.setThreadNamePrefix("huolala-sync-");
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(25);
        return pool;
    }
}
