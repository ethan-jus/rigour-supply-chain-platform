package com.rigour.settings.infrastructure.config;

import com.rigour.settings.application.service.StatutoryCalendarService;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class StatutoryCalendarScheduling {
    private final StatutoryCalendarService service;

    public StatutoryCalendarScheduling(StatutoryCalendarService service) {
        this.service = service;
    }

    @Scheduled(
            initialDelayString = "${rigour.settings.calendar.initial-delay-ms:30000}",
            fixedDelayString = "${rigour.settings.calendar.refresh-delay-ms:21600000}")
    public void refresh() {
        service.refreshTrackedYears();
    }
}
