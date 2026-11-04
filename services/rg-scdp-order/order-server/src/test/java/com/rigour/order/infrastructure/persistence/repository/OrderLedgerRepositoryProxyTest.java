package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.rigour.order.infrastructure.persistence.mapper.OrderCostSourceMapper;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;

class OrderLedgerRepositoryProxyTest {
    @Test
    void ledgerRepositorySupportsTheStartupClassProxy() {
        new ApplicationContextRunner()
                .withBean(PersistenceExceptionTranslationPostProcessor.class, () -> {
                    var processor = new PersistenceExceptionTranslationPostProcessor();
                    processor.setProxyTargetClass(true);
                    return processor;
                })
                .withBean(MybatisOrderLedgerSourceStore.class, () -> new MybatisOrderLedgerSourceStore(
                        mock(OrderCostSourceMapper.class), mock(LogisticsWarehouseDataScope.class)))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(AopUtils.isCglibProxy(context.getBean(MybatisOrderLedgerSourceStore.class))).isTrue();
                });
    }
}
