package com.rigour.erp.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.rigour.erp.infrastructure.persistence.mapper.CostLedgerReferenceMapper;
import com.rigour.erp.infrastructure.persistence.mapper.ErpCostReferenceMapper;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

class CostLedgerRepositoryProxyTest {
    @Test
    void allNewCostRepositoriesSupportSpringClassProxies() {
        new ApplicationContextRunner()
                .withBean(PersistenceExceptionTranslationPostProcessor.class, () -> {
                    var processor = new PersistenceExceptionTranslationPostProcessor();
                    processor.setProxyTargetClass(true);
                    return processor;
                })
                .withBean(JdbcCostLedgerReferences.class, () -> new JdbcCostLedgerReferences(
                        mock(CostLedgerReferenceMapper.class), mock(ErpCostReferenceMapper.class),
                        mock(ErpWarehouseDataScope.class)))
                .withBean(JdbcProcurementCostSources.class,
                        () -> new JdbcProcurementCostSources(mock(JdbcTemplate.class)))
                .withBean(JdbcProcurementGoodsCosts.class, () -> new JdbcProcurementGoodsCosts(
                        mock(JdbcTemplate.class), new ObjectMapper(), mock(ErpWarehouseDataScope.class)))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(AopUtils.isCglibProxy(context.getBean(JdbcCostLedgerReferences.class))).isTrue();
                    assertThat(AopUtils.isCglibProxy(context.getBean(JdbcProcurementCostSources.class))).isTrue();
                    assertThat(AopUtils.isCglibProxy(context.getBean(JdbcProcurementGoodsCosts.class))).isTrue();
                });
    }
}
