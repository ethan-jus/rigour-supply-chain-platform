package com.rigour.merchant.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;
import org.springframework.aop.MethodBeforeAdvice;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;

class MybatisPlusCrmRepositoryProxyTest {

    @Test
    void supportsClassBasedSpringProxyForTransactionalMethods() {
        ProxyFactory proxyFactory = new ProxyFactory();
        proxyFactory.setTargetClass(MybatisPlusCrmRepository.class);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice((MethodBeforeAdvice) (method, args, target) -> {
            // 仅触发与运行时事务代理一致的 CGLIB 类代理创建过程。
        });

        Object proxy = assertDoesNotThrow(() -> {
            return proxyFactory.getProxy();
        });

        assertThat(AopUtils.isCglibProxy(proxy)).isTrue();
    }
}
