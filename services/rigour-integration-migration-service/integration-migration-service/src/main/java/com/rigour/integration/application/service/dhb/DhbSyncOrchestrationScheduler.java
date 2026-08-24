package com.rigour.integration.application.service.dhb;

import com.rigour.integration.api.v1.model.DhbSyncOrchestrationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** 订货宝统一同步定时入口；ERP/CRM/Order 不再各自抢连接器时间窗。 */
public final class DhbSyncOrchestrationScheduler {
    private static final Logger log = LoggerFactory.getLogger(DhbSyncOrchestrationScheduler.class);

    private final DhbSyncOrchestrationService service;
    private final DhbSyncOrchestrationProperties properties;

    public DhbSyncOrchestrationScheduler(DhbSyncOrchestrationService service,
                                         DhbSyncOrchestrationProperties properties) {
        this.service = service;
        this.properties = properties;
        log.info("订货宝统一同步编排定时配置 enabled={} cron={} maxPages={}",
                properties.isEnabled(), properties.getCron(), properties.getMaxPages());
    }

    @Scheduled(cron = "${rigour.integration.dhb.orchestration.cron:"
            + DhbSyncOrchestrationProperties.DEFAULT_CRON + "}")
    public void synchronize() {
        if (!properties.isEnabled()) {
            log.info("订货宝统一同步编排未启用，跳过本次调度");
            return;
        }
        try {
            DhbSyncOrchestrationResult result = service.runScheduled();
            log.info("订货宝统一同步编排完成 batchId={} status={} tenantCount={}",
                    result.batchId(), result.status(), result.tenants().size());
        } catch (RuntimeException error) {
            log.error("订货宝统一同步编排失败 reason={}", oneLine(error.getMessage()), error);
        }
    }

    private static String oneLine(String value) {
        return value == null ? "-" : value.replace('\r', ' ').replace('\n', ' ');
    }
}
