package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.*;

import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.math.BigDecimal;
import java.util.*;

class HrPayrollDeterminationServiceTest {
    HrPayrollService payroll = mock(HrPayrollService.class);
    HrPayrollSchemeService schemes = mock(HrPayrollSchemeService.class);
    HrPayrollDeterminationService service;
    JdbcTemplate jdbc;
    PayrollInputCommand input =
            new PayrollInputCommand(0, null, new BigDecimal("3200"), BigDecimal.ZERO, "统一核定说明");
    PayrollSchemeFactsCommand facts =
            new PayrollSchemeFactsCommand(
                    0, null, List.of(), "ignored", new BigDecimal("80"), new BigDecimal("100"));

    @BeforeEach
    void setup() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:determination_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE changes(kind VARCHAR(40))");
        var proxy = new ProxyFactory(new HrPayrollDeterminationService(payroll, schemes));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
                new TransactionInterceptor(
                        new DataSourceTransactionManager(ds),
                        new AnnotationTransactionAttributeSource()));
        service = (HrPayrollDeterminationService) proxy.getProxy();
        when(payroll.saveInput(anyString(), anyString(), any()))
                .thenAnswer(
                        a -> {
                            jdbc.update("INSERT INTO changes VALUES ('input')");
                            return a.getArgument(2);
                        });
    }

    @Test
    void sharesOneReasonAndCommitsBothInputsForOneEmployeeAndMonth() {
        when(schemes.saveFacts(anyString(), anyString(), any()))
                .thenAnswer(
                        a -> {
                            jdbc.update("INSERT INTO changes VALUES ('facts')");
                            return new PayrollSchemeFactsView(
                                    1,
                                    null,
                                    List.of(),
                                    "统一核定说明",
                                    new BigDecimal("80"),
                                    new BigDecimal("100"));
                        });
        var result =
                service.save("2026-09", "EMP-A", new PayrollDeterminationCommand(input, facts));
        verify(schemes)
                .saveFacts(
                        eq("2026-09"),
                        eq("EMP-A"),
                        argThat(
                                f ->
                                        f.reason().equals(input.reason())
                                                && f.performanceCompletionPercent()
                                                                .compareTo(new BigDecimal("80"))
                                                        == 0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM changes", Integer.class)).isEqualTo(2);
        assertThat(result.input()).isEqualTo(input);
        assertThat(result.facts().revision()).isEqualTo(1);
    }

    @Test
    void aFactsConflictRollsBackMonetaryChangesInTheSameTransaction() {
        when(schemes.saveFacts(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("月度指标已更新"));
        assertThatThrownBy(
                        () ->
                                service.save(
                                        "2026-09",
                                        "EMP-A",
                                        new PayrollDeterminationCommand(input, facts)))
                .hasMessageContaining("月度指标已更新");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM changes", Integer.class)).isZero();
    }

    @Test
    void aLockedOrUnauthorizedAmountChangeCannotWriteFacts() {
        when(payroll.saveInput(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("已确认月份"));
        assertThatThrownBy(
                        () ->
                                service.save(
                                        "2026-09",
                                        "EMP-A",
                                        new PayrollDeterminationCommand(input, facts)))
                .hasMessageContaining("已确认");
        verifyNoInteractions(schemes);
    }

    @Test
    void supportsLegacyPayrollWithoutSchemeFacts() {
        assertThat(
                        service.save(
                                        "2026-09",
                                        "EMP-A",
                                        new PayrollDeterminationCommand(input, null))
                                .facts())
                .isNull();
        verifyNoInteractions(schemes);
    }
}
