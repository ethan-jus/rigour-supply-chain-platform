package com.rigour.sales.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="rigour.sales.fieldwork.ai-enabled",havingValue="true")
public class SalesRecordingAnalysisConfiguration {
    @Bean
    ThreadPoolTaskScheduler salesRecordingAnalysisScheduler() {
        var scheduler=new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("sales-recording-analysis-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(70);
        return scheduler;
    }
}
