package com.rigour.order.infrastructure.integration;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;

import java.util.concurrent.atomic.AtomicInteger;

class LogisticsRequestCacheTest {
    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void onlyIdenticalReferencesWithinOneRequestAreReused() {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
        var reads = new AtomicInteger();
        assertThat(LogisticsRequestCache.get("T1", "read", "cities", "all", reads::incrementAndGet))
                .isEqualTo(1);
        assertThat(LogisticsRequestCache.get("T1", "read", "cities", "all", reads::incrementAndGet))
                .isEqualTo(1);
        assertThat(LogisticsRequestCache.get("T2", "read", "cities", "all", reads::incrementAndGet))
                .isEqualTo(2);
        assertThat(
                        LogisticsRequestCache.get(
                                "T1", "write", "cities", "all", reads::incrementAndGet))
                .isEqualTo(3);
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
        assertThat(LogisticsRequestCache.get("T1", "read", "cities", "all", reads::incrementAndGet))
                .isEqualTo(4);
    }

    @Test
    void noRequestDoesNotReuseDataAndFailuresAreNotCached() {
        var reads = new AtomicInteger();
        LogisticsRequestCache.get("T", "read", "city", "WH", reads::incrementAndGet);
        LogisticsRequestCache.get("T", "read", "city", "WH", reads::incrementAndGet);
        assertThat(reads.get()).isEqualTo(2);
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
        assertThatThrownBy(
                        () ->
                                LogisticsRequestCache.get(
                                        "T",
                                        "read",
                                        "city",
                                        "WH",
                                        () -> {
                                            throw new IllegalStateException();
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(LogisticsRequestCache.get("T", "read", "city", "WH", reads::incrementAndGet))
                .isEqualTo(3);
    }
}
