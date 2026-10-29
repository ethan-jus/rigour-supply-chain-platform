package com.rigour.merchant.infrastructure.scheduling;

import com.rigour.merchant.application.service.StoreLifecycleService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="rigour.crm.store-lifecycle.enabled", havingValue="true")
public class StoreLifecycleScheduler {
    private final StoreLifecycleService service;
    public StoreLifecycleScheduler(StoreLifecycleService service) { this.service=service; }
    @Scheduled(fixedDelayString="${rigour.crm.store-lifecycle.delay:10000}", initialDelayString="${rigour.crm.store-lifecycle.initial-delay:15000}")
    public void check() { service.tick(); }
}
