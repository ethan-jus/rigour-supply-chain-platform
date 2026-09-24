package com.rigour.tenant.iam.infrastructure.persistence.auth;

import com.rigour.tenant.iam.api.controller.auth.SelfPasswordController;
import com.rigour.tenant.iam.application.port.out.PasswordHasher;
import com.rigour.tenant.iam.application.port.out.SelfPasswordStore;
import com.rigour.tenant.iam.application.service.auth.SelfPasswordService;
import com.rigour.tenant.iam.infrastructure.security.session.PasswordAuthenticationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 使用与运行环境一致的仓储代理，覆盖 Controller → Service → Store 装配链。 */
class JdbcSelfPasswordStoreContextTest {
    @Test
    void passwordControllerLoadsWithClassBasedRepositoryProxy() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(PersistenceExceptionTranslationPostProcessor.class, () -> {
                var processor = new PersistenceExceptionTranslationPostProcessor();
                processor.setProxyTargetClass(true);
                return processor;
            });
            context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
            context.registerBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
            context.registerBean(PasswordHasher.class, () -> mock(PasswordHasher.class));
            context.registerBean(PasswordAuthenticationProperties.class);
            context.register(JdbcSelfPasswordStore.class, SelfPasswordService.class, SelfPasswordController.class);
            context.refresh();
            assertThat(AopUtils.isCglibProxy(context.getBean(SelfPasswordStore.class))).isTrue();
            assertThat(context.getBean(SelfPasswordController.class)).isNotNull();
        }
    }
}
