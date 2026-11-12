package com.rigour.integration.infrastructure.thirdparty.huolala.config;

import com.rigour.integration.application.service.thirdparty.huolala.HuolalaService.DeploymentSettings;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
public class HuolalaConfiguration {
    @Bean
    public DeploymentSettings huolalaDeploymentSettings(Environment environment) {
        return new DeploymentSettings(
                property(environment, "enterprise-id"),
                property(environment, "enterprise-name"),
                property(environment, "secret-ref"),
                property(environment, "redirect-uri"),
                Boolean.valueOf(
                        java.util.Objects.toString(property(environment, "sandbox"), "false")),
                environment.matchesProfiles("dev", "local")
                        && !environment.matchesProfiles("prod", "production"));
    }

    private static String property(Environment environment, String name) {
        return environment.getProperty(
                "rigour.thirdparty.huolala." + name,
                environment.getProperty("rigour.huolala." + name));
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
