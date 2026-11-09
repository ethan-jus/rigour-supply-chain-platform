package com.rigour.order.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rigour.order.api.LogisticsImportController;
import com.rigour.order.application.service.cost.LogisticsImportService;
import com.rigour.order.application.service.cost.OrderLogisticsCostService;
import com.rigour.order.application.service.cost.OrderLogisticsLedgerService;
import com.rigour.order.infrastructure.persistence.mapper.OrderCostSourceMapper;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;

class OrderLedgerRepositoryProxyTest {
    @Test
    void carrierImportControllerWiresWithRepositoryAndTransactionClassProxies() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM order_logistics_cost WHERE tenant_id=? AND"
                                + " carrier_code='HLL' AND tracking_no=?",
                        Long.class,
                        "T1",
                        "HL000001"))
                .thenReturn(0L);
        new ApplicationContextRunner()
                .withUserConfiguration(CarrierImportProxyConfiguration.class)
                .withBean(
                        PersistenceExceptionTranslationPostProcessor.class,
                        () -> {
                            var processor = new PersistenceExceptionTranslationPostProcessor();
                            processor.setProxyTargetClass(true);
                            return processor;
                        })
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
                .withBean(
                        DataSourceTransactionManager.class,
                        () -> new DataSourceTransactionManager(mock(DataSource.class)))
                .withBean(
                        OrderLogisticsCostService.class,
                        () -> mock(OrderLogisticsCostService.class))
                .withBean(
                        OrderLogisticsLedgerService.class,
                        () -> mock(OrderLogisticsLedgerService.class))
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(LogisticsImportController.class);
                            var store = context.getBean(JdbcLogisticsImportStore.class);
                            assertThat(AopUtils.isCglibProxy(store)).isTrue();
                            assertThat(
                                            AopUtils.isCglibProxy(
                                                    context.getBean(LogisticsImportService.class)))
                                    .isTrue();
                            assertThat(store.trackingExists("T1", "HL000001")).isFalse();
                        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({
        JdbcLogisticsImportStore.class,
        LogisticsImportService.class,
        LogisticsImportController.class
    })
    static class CarrierImportProxyConfiguration {}

    @Test
    void ledgerRepositorySupportsTheStartupClassProxy() {
        new ApplicationContextRunner()
                .withBean(
                        PersistenceExceptionTranslationPostProcessor.class,
                        () -> {
                            var processor = new PersistenceExceptionTranslationPostProcessor();
                            processor.setProxyTargetClass(true);
                            return processor;
                        })
                .withBean(
                        MybatisOrderLedgerSourceStore.class,
                        () ->
                                new MybatisOrderLedgerSourceStore(
                                        mock(OrderCostSourceMapper.class),
                                        mock(LogisticsWarehouseDataScope.class)))
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(
                                            AopUtils.isCglibProxy(
                                                    context.getBean(
                                                            MybatisOrderLedgerSourceStore.class)))
                                    .isTrue();
                        });
    }
}
