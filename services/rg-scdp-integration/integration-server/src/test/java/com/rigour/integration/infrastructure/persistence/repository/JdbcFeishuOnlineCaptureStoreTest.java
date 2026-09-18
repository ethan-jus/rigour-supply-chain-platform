package com.rigour.integration.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;

import com.rigour.integration.api.v1.model.FeishuReconciliationModels.CaptureSummary;
import com.rigour.integration.application.port.out.FeishuOnlineCaptureStore;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcFeishuOnlineCaptureStoreTest {
    @Test
    void persistsOnlyEvidenceUsingBinaryUuidAndUtcDates() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID tenant = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        var summary = summary(true);
        new JdbcFeishuOnlineCaptureStore(jdbc).save(tenant, actor, summary, "{\"tables\":[]}");
        var call = mockingDetails(jdbc).getInvocations().iterator().next();
        assertThat((String) call.getRawArguments()[0]).contains("INSERT INTO integration_feishu_online_capture")
                .doesNotContain("integration_feishu_import", "UPDATE", "DELETE");
        Object[] values = (Object[]) call.getRawArguments()[1];
        assertThat((byte[]) values[1]).containsExactly(ByteBuffer.allocate(16)
                .putLong(tenant.getMostSignificantBits()).putLong(tenant.getLeastSignificantBits()).array());
        assertThat(values[6]).isEqualTo(LocalDateTime.parse("2026-09-14T01:00:00"));
        assertThat(values[7]).isEqualTo(LocalDateTime.parse("2026-09-14T01:00:05"));
        assertThat(values[13]).isEqualTo("{\"tables\":[]}");
    }

    @Test
    void rejectsIncompleteOrIdentityMissingWrites() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var store = new JdbcFeishuOnlineCaptureStore(jdbc);
        assertThatThrownBy(() -> store.save(UUID.randomUUID(), UUID.randomUUID(), summary(false), "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.save(null, UUID.randomUUID(), summary(true), "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.save(UUID.randomUUID(), null, summary(true), "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void repositorySupportsRealSpringClassProxy() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(PersistenceExceptionTranslationPostProcessor.class, () -> {
                var processor = new PersistenceExceptionTranslationPostProcessor();
                processor.setProxyTargetClass(true);
                return processor;
            });
            context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
            context.registerBean(JdbcFeishuOnlineCaptureStore.class);
            context.refresh();
            assertThat(AopUtils.isCglibProxy(context.getBean(FeishuOnlineCaptureStore.class))).isTrue();
        }
    }

    private static CaptureSummary summary(boolean complete) {
        return new CaptureSummary(UUID.randomUUID(), "fixture", "销售来源", "https://feishu.cn/base/fixture",
                Instant.parse("2026-09-14T01:00:00Z"), Instant.parse("2026-09-14T01:00:05Z"),
                complete, false, "a".repeat(64), 3, 2);
    }
}
