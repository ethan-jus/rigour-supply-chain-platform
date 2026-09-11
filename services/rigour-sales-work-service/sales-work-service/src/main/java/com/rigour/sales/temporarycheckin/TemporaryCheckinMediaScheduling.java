package com.rigour.sales.temporarycheckin;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 媒体派生独占一个后台线程，不被云端转写长请求阻塞，也不并行转码多个大录音。 */
@Configuration(proxyBeanMethods=false)
class TemporaryCheckinMediaScheduling {
    @Bean("temporaryCheckinMediaScheduler")
    @ConditionalOnProperty(prefix="rigour.sales.temporary-checkin",name="enabled",havingValue="true")
    ThreadPoolTaskScheduler mediaScheduler() {
        ThreadPoolTaskScheduler scheduler=new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("checkin-media-");
        scheduler.setDaemon(true);
        return scheduler;
    }

    @Bean("temporaryCheckinAnalysisScheduler")
    @ConditionalOnProperty(prefix="rigour.sales.temporary-checkin.ai",name="enabled",havingValue="true")
    ThreadPoolTaskScheduler analysisScheduler() {
        ThreadPoolTaskScheduler scheduler=new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("checkin-analysis-");
        scheduler.setDaemon(true);
        return scheduler;
    }
}
