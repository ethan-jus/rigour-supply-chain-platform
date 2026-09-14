package com.rigour.tenant.iam.infrastructure.persistence.repository;

import com.rigour.tenant.iam.application.port.out.IamBiIdentityStore;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 覆盖生产仓储异常翻译代理的加载链，避免普通构造测试漏掉 final 类启动失败。 */
class JdbcIamBiIdentityStoreContextTest {
    @Test
    void repositoryLoadsWithClassBasedPersistenceExceptionTranslation() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(PersistenceExceptionTranslationPostProcessor.class, () -> {
                var processor = new PersistenceExceptionTranslationPostProcessor();
                processor.setProxyTargetClass(true);
                return processor;
            });
            context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
            context.registerBean(JdbcIamBiIdentityStore.class);
            context.refresh();
            var store = context.getBean(IamBiIdentityStore.class);
            assertThat(AopUtils.isCglibProxy(store)).isTrue();
            assertThatThrownBy(() -> store.read(UUID.randomUUID(), UUID.randomUUID()))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Exact active employee binding required");
        }
    }
}
