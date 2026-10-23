package com.rigour.hr.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.service.PayrollSchemeTestFixture;
import com.rigour.hr.infrastructure.persistence.mapper.*;

import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

public class PayrollSchemePersistenceTest {
    MybatisPlusHrPayrollSchemeRepository repository;
    JdbcTemplate jdbc;

    @BeforeEach
    void setup() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:scheme_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        jdbc = new JdbcTemplate(ds);
        String ddl =
                new ClassPathResource("db/migration/V20__hr_payroll_scheme_versions.sql")
                        .getContentAsString(StandardCharsets.UTF_8)
                        .replace(
                                "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci",
                                "")
                        .replace(" JSON ", " LONGTEXT ");
        try (var connection = ds.getConnection()) {
            connection.createStatement().execute(ddl);
        }
        jdbc.execute(
                "CREATE ALIAS UTC_TIMESTAMP FOR"
                    + " 'com.rigour.hr.infrastructure.persistence.repository.PayrollSchemePersistenceTest.utcNow'");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(HrPayrollSchemeMapper.class);
        config.addMapper(HrPayrollSchemeInputMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        var target =
                new MybatisPlusHrPayrollSchemeRepository(
                        session.getMapper(HrPayrollSchemeMapper.class),
                        session.getMapper(HrPayrollSchemeInputMapper.class),
                        mock(HrPayrollPersistenceSupport.class),
                        JsonMapper.builder().build());
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
                new TransactionInterceptor(
                        new DataSourceTransactionManager(ds),
                        new AnnotationTransactionAttributeSource()));
        repository = (MybatisPlusHrPayrollSchemeRepository) proxy.getProxy();
    }

    public static LocalDateTime utcNow(int precision) {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    PayrollSchemeSaveCommand command(String request, Integer revision) {
        return new PayrollSchemeSaveCommand(
                request, revision, null, PayrollSchemeTestFixture.definition(), "测试核定");
    }

    @Test
    void tenantIsolationIdempotencyRevisionAndImmutablePublish() {
        var c = command("request", 0);
        var saved = repository.save("tenant-a", null, c, "actor");
        assertThat(repository.save("tenant-a", null, c, "actor").id()).isEqualTo(saved.id());
        assertThat(repository.versions("tenant-b")).isEmpty();
        var edited = repository.save("tenant-a", saved.id(), c, "actor");
        assertThat(edited.revision()).isEqualTo(1);
        assertThatThrownBy(() -> repository.save("tenant-a", saved.id(), c, "actor"))
                .hasMessageContaining("更新");
        var published =
                repository.publish(
                        "tenant-a", saved.id(), new PayrollSchemePublishCommand(1, "审核"), "actor");
        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(
                        repository.publish(
                                "tenant-a",
                                saved.id(),
                                new PayrollSchemePublishCommand(1, "审核"),
                                "actor"))
                .isEqualTo(published);
        assertThatThrownBy(
                        () ->
                                repository.save(
                                        "tenant-a", saved.id(), command("request", 2), "actor"))
                .hasMessageContaining("已发布");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM hr_payroll_scheme_event", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void monthlyCompletionAndCommissionAdjustmentRoundTripWithTenantMonthIsolation() {
        var command =
                new PayrollSchemeFactsCommand(
                        0,
                        null,
                        List.of(),
                        "月度核定",
                        new java.math.BigDecimal("87.5"),
                        new java.math.BigDecimal("-30.25"));
        repository.saveFacts("tenant-a", "EMP", "2026-10", command, "actor");
        var loaded = repository.facts("tenant-a", "EMP", "2026-10");
        assertThat(loaded.performanceCompletionPercent()).isEqualByComparingTo("87.5");
        assertThat(loaded.commissionAdjustment()).isEqualByComparingTo("-30.25");
        assertThat(loaded.revision()).isEqualTo(1);
        assertThat(repository.facts("tenant-b", "EMP", "2026-10").performanceCompletionPercent())
                .isNull();
        assertThat(repository.facts("tenant-a", "EMP", "2026-11").commissionAdjustment()).isNull();
        assertThatThrownBy(
                        () -> repository.saveFacts("tenant-a", "EMP", "2026-10", command, "actor"))
                .hasMessageContaining("月度指标已更新");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM hr_payroll_scheme_event WHERE event_type ="
                                        + " 'MONTHLY_FACTS'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void completionIsIsolatedByEmployeeWithinOneTenantAndMonth() {
        repository.saveFacts(
                "tenant-a",
                "EMP-A",
                "2026-10",
                new PayrollSchemeFactsCommand(
                        0, null, List.of(), "A核定", new java.math.BigDecimal("80")),
                "actor");
        repository.saveFacts(
                "tenant-a",
                "EMP-B",
                "2026-10",
                new PayrollSchemeFactsCommand(
                        0, null, List.of(), "B核定", new java.math.BigDecimal("110")),
                "actor");
        assertThat(repository.facts("tenant-a", "EMP-A", "2026-10").performanceCompletionPercent())
                .isEqualByComparingTo("80");
        assertThat(repository.facts("tenant-a", "EMP-B", "2026-10").performanceCompletionPercent())
                .isEqualByComparingTo("110");
        assertThat(repository.facts("tenant-a", "EMP-C", "2026-10").performanceCompletionPercent())
                .isNull();
        assertThat(repository.facts("tenant-a", "EMP-B", "2026-11").performanceCompletionPercent())
                .isNull();
    }

    @Test
    void hrMetricScoringBandsRoundTripWithoutChangingPublishedHistory() {
        var json = JsonMapper.builder().build();
        tools.jackson.databind.node.ObjectNode node =
                (tools.jackson.databind.node.ObjectNode)
                        json.valueToTree(PayrollSchemeTestFixture.definition());
        var bands =
                List.of(
                        new PayrollMetricStep(
                                java.math.BigDecimal.ZERO,
                                null,
                                true,
                                false,
                                "FIXED",
                                new java.math.BigDecimal("1.73")));
        node.set(
                "manager",
                json.valueToTree(
                        new PayrollManagerPolicy(
                                List.of(
                                        new PayrollMetricDefinition(
                                                "store",
                                                "建联门店",
                                                "MANUAL_CONFIRMED",
                                                null,
                                                new java.math.BigDecimal("100"),
                                                bands,
                                                null,
                                                "CONTACTED_STORE_COUNT",
                                                "TIERED")),
                                new PayrollProtectionPolicy("MONTHS", 1, true),
                                null,
                                List.of(),
                                "ACTUAL_COMPLETION")));
        var definition = json.treeToValue(node, PayrollSchemeDefinition.class);
        var saved =
                repository.save(
                        "tenant-a",
                        null,
                        new PayrollSchemeSaveCommand("scoring", 0, null, definition, "HR配置"),
                        "actor");
        var published =
                repository.publish(
                        "tenant-a",
                        saved.id(),
                        new PayrollSchemePublishCommand(saved.revision(), "发布"),
                        "actor");
        var loaded =
                repository.versions("tenant-a").stream()
                        .filter(v -> v.id().equals(published.id()))
                        .findFirst()
                        .orElseThrow();
        assertThat(loaded.definition().manager().metrics().getFirst().scoringMode())
                .isEqualTo("TIERED");
        assertThat(
                        loaded.definition()
                                .manager()
                                .metrics()
                                .getFirst()
                                .steps()
                                .getFirst()
                                .coefficient())
                .isEqualByComparingTo("1.73");
        assertThat(repository.versions("tenant-b")).isEmpty();
    }

    @Test
    void failedAuditRollsBackMonthlyFacts() {
        jdbc.execute("DROP TABLE hr_payroll_scheme_event");
        assertThatThrownBy(
                () ->
                        repository.saveFacts(
                                "tenant-a",
                                "EMP",
                                "2026-10",
                                new PayrollSchemeFactsCommand(0, null, List.of(), "核定"),
                                "actor"));
        assertThat(repository.facts("tenant-a", "EMP", "2026-10").revision()).isZero();
    }

    @Test
    void stoppingKeepsDefinitionAndHistoricalVersion() {
        var saved = repository.save("t", null, command("stop-test", 0), "actor");
        var published =
                repository.publish(
                        "t", saved.id(), new PayrollSchemePublishCommand(0, "发布"), "actor");
        var stopped =
                repository.stop(
                        "t",
                        saved.id(),
                        new PayrollSchemeStopCommand(
                                published.revision(), LocalDate.of(2027, 1, 1), "停用"),
                        "actor");
        assertThat(stopped.definition()).isEqualTo(published.definition());
        assertThat(stopped.stopFrom()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(stopped.status()).isEqualTo("PUBLISHED");
    }
}
