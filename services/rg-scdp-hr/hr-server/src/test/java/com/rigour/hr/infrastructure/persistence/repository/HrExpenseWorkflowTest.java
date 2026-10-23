package com.rigour.hr.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.service.HrExpenseRules;
import com.rigour.hr.infrastructure.persistence.mapper.CostAttachmentMapper;
import com.rigour.hr.infrastructure.persistence.mapper.HrExpenseMapper;

import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** 真正执行费用保存/确认/作废及分析源查询；不启动 Nacos 或连接共享库。 */
class HrExpenseWorkflowTest {
    SingleConnectionDataSource ds;
    JdbcTemplate jdbc;
    MybatisPlusHrExpenseRepository store;
    MybatisPlusCostAnalyticsSource source;
    TransactionTemplate tx;
    MybatisPlusCostAttachmentRepository attachments;
    final PayrollEmployeeView employee =
            new PayrollEmployeeView("E1", "测试员工甲", 10L, "测试城市部", "武汉", null, null, null);

    @BeforeEach
    void setup() throws Exception {
        ds =
                new SingleConnectionDataSource(
                        "jdbc:h2:mem:workflow_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                        "sa",
                        "",
                        true);
        jdbc = new JdbcTemplate(ds);
        String ddl =
                Files.readString(
                        Path.of(
                                "src/main/resources/db/migration/V10__hr_payroll_and_expenses.sql"));
        ddl = ddl.substring(ddl.indexOf("CREATE TABLE hr_expense ("));
        ddl =
                ddl.substring(0, ddl.indexOf(';') + 1)
                        .replace(
                                "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci",
                                "")
                        .replace(" JSON ", " LONGTEXT ");
        jdbc.execute(ddl);
        String extra =
                Files.readString(
                                Path.of(
                                        "src/main/resources/db/migration/V25__expense_details_and_evidence.sql"))
                        .replace(
                                "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci",
                                "");
        // No rows exist yet; the MySQL-only legacy DATE_FORMAT backfill is covered by MySQL
        // acceptance.
        extra = extra.replaceAll("UPDATE hr_expense[^;]+;", "");
        jdbc.execute(extra);
        jdbc.execute(
                "CREATE TABLE hr_expense_guard(tenant_id VARCHAR(64) PRIMARY KEY, version BIGINT"
                        + " NOT NULL DEFAULT 0)");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(HrExpenseMapper.class);
        config.addMapper(CostAttachmentMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        var mapper = session.getMapper(HrExpenseMapper.class);
        attachments =
                new MybatisPlusCostAttachmentRepository(
                        session.getMapper(CostAttachmentMapper.class), mapper);
        var scope = mock(HrDataScope.class);
        when(scope.apply(any(), anyString()))
                .thenAnswer(
                        i ->
                                ((com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<?>)
                                                i.getArgument(0))
                                        .eq("employee_code", "E1"));
        store =
                new MybatisPlusHrExpenseRepository(
                        mapper,
                        mock(HrPayrollPersistenceSupport.class),
                        JsonMapper.builder().build(),
                        scope);
        source = new MybatisPlusCostAnalyticsSource(mapper);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    @AfterEach
    void close() {
        ds.destroy();
    }

    HrExpenseCommand command(
            String request,
            Integer revision,
            String type,
            String month,
            String category,
            String link,
            String amount) {
        var d =
                new HrExpenseDetails(
                        category,
                        "测试客户拜访 / 费用核对",
                        "测试收款单位",
                        "A4复印纸",
                        new BigDecimal("10"),
                        "包",
                        new BigDecimal("14.63"),
                        "测试场景按实际票据",
                        "TEST-TRIP-1",
                        LocalDate.of(2026, 10, 1),
                        LocalDate.of(2026, 10, 3),
                        "武汉",
                        "成都",
                        "TEST-INVOICE-1",
                        null,
                        "DELIVERY",
                        link == null
                                ? null
                                : new HrExpenseCostReference("ORDER", "LOGISTICS", link, 1));
        return new HrExpenseCommand(
                request,
                revision,
                "隔离验证费用",
                type,
                "E1",
                "4201",
                "武汉",
                LocalDate.of(2026, 10, 1),
                new BigDecimal(amount),
                "UNPAID",
                "虚构记录",
                "测试修改",
                month,
                d);
    }

    HrExpenseDetailView save(String id, HrExpenseCommand c) {
        HrExpenseRules.validate(c, false, 0);
        return tx.execute(s -> store.save("T1", id, c, employee, "测试录入人"));
    }

    HrExpenseDetailView confirm(HrExpenseDetailView row) {
        return tx.execute(
                s ->
                        store.expenseTransition(
                                "T1",
                                row.id(),
                                new PayrollActionCommand(row.revision(), "测试确认"),
                                "CONFIRMED",
                                "测试财务"));
    }

    HrExpensePageView list() {
        return store.expenses(
                "T1",
                new HrExpenseQuery(
                        0, 20, null, null, null, null, null, null, null, null, null, null,
                        "2026-10"),
                "hr:expense:read");
    }

    @Test
    void attachmentsUseHrMigrationTableAndStayWithinExpenseAndTenant() {
        var row =
                save(
                        null,
                        command(
                                UUID.randomUUID().toString(),
                                null,
                                "TRAVEL",
                                "2026-10",
                                "expense",
                                null,
                                "320.00"));
        assertThat(attachments.list("T1", row.id())).isEmpty();
        var file =
                new HrExpenseAttachmentView(
                        UUID.randomUUID().toString(),
                        "模拟行程.pdf",
                        "application/pdf",
                        100L,
                        "测试录入人",
                        Instant.parse("2026-10-21T01:02:03Z"));
        tx.executeWithoutResult(
                s ->
                        attachments.add(
                                "T1", row.id(), row.revision(), file, "test/private-trip.pdf"));
        assertThat(attachments.list("T1", row.id())).containsExactly(file);
        assertThat(attachments.fileKey("T1", row.id(), file.id()))
                .isEqualTo("test/private-trip.pdf");
        assertThat(attachments.list("T2", row.id())).isEmpty();
        assertThat(attachments.list("T1", "another-expense")).isEmpty();
        assertThatThrownBy(() -> attachments.fileKey("T2", row.id(), file.id()))
                .isInstanceOf(com.rigour.shared.core.exception.BusinessException.class);
        assertThat(list().items().getFirst().revision()).isEqualTo(row.revision() + 1);
    }

    @Test
    void lifecyclePublishesExactAmountWithOneIdempotentRowAndAuditableActors() {
        var c =
                command(
                        UUID.randomUUID().toString(),
                        null,
                        "OFFICE_SUPPLIES",
                        "2026-10",
                        "office",
                        null,
                        "146.30");
        var row = save(null, c);
        var retry = save(null, c);
        assertThat(retry.id()).isEqualTo(row.id());
        assertThat(list().total()).isEqualTo(1);
        assertThat(list().confirmedAmount()).isZero();
        assertThat(row.createdBy()).isEqualTo("测试录入人");
        assertThat(row.createdTime()).isNotNull();
        row = confirm(row);
        assertThat(row.createdBy()).isEqualTo("测试录入人");
        assertThat(row.updatedBy()).isEqualTo("测试财务");
        assertThat(row.data().employeeCode()).isEqualTo(employee.employeeCode());
        assertThat(list().confirmedAmount()).isEqualByComparingTo("146.30");
        var projection = source.page("T1", "");
        assertThat(projection.items()).hasSize(1);
        assertThat(projection.items().getFirst().get("amount")).isEqualTo("146.30");
        assertThat(projection.items().getFirst().get("data_json"))
                .contains("office", "146.30", "2026-10");
        var confirmed = row;
        var updated =
                save(
                        row.id(),
                        command(
                                c.requestId(),
                                row.revision(),
                                "OFFICE_SUPPLIES",
                                "2026-10",
                                "office",
                                null,
                                "160.00"));
        assertThat(updated.statusCode()).isEqualTo("DRAFT");
        assertThat(updated.createdBy()).isEqualTo("测试录入人");
        assertThat(updated.updatedBy()).isEqualTo("测试录入人");
        assertThat(list().confirmedAmount()).isZero();
        assertThatThrownBy(() -> confirm(confirmed)).hasMessageContaining("版本");
        var finalRow = confirm(updated);
        tx.executeWithoutResult(
                s ->
                        store.expenseTransition(
                                "T1",
                                finalRow.id(),
                                new PayrollActionCommand(finalRow.revision(), "重复登记测试作废"),
                                "VOID",
                                "测试财务"));
        assertThat(list().registeredAmount()).isZero();
        assertThat(list().confirmedAmount()).isZero();
        assertThat(source.page("T1", "").items().getFirst().get("status_code")).isEqualTo("VOID");
        assertThat(source.page("OTHER", "").items()).isEmpty();
        assertThatThrownBy(() -> store.expenseDetail("OTHER", rowId(finalRow), "hr:expense:read"))
                .hasMessageContaining("不存在或无权限");
    }

    String rowId(HrExpenseDetailView r) {
        return r.id();
    }

    @Test
    void originalCanOnlyBeClaimedOnceAndRollbackDoesNotAdvanceAnalyticsVersion() {
        String original = UUID.randomUUID().toString();
        var first =
                confirm(
                        save(
                                null,
                                command(
                                        UUID.randomUUID().toString(),
                                        null,
                                        "FREIGHT",
                                        "2026-10",
                                        "freight",
                                        original,
                                        "320.00")));
        var second =
                save(
                        null,
                        command(
                                UUID.randomUUID().toString(),
                                null,
                                "FREIGHT",
                                "2026-10",
                                "freight",
                                original,
                                "320.00"));
        String version = source.version("T1");
        assertThatThrownBy(() -> confirm(second)).hasMessageContaining("已关联其他报销");
        assertThat(source.version("T1")).isEqualTo(version);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hr_expense_cost_link", Integer.class))
                .isEqualTo(1);
        tx.executeWithoutResult(
                s ->
                        store.expenseTransition(
                                "T1",
                                first.id(),
                                new PayrollActionCommand(first.revision(), "重复登记作废"),
                                "VOID",
                                "财务"));
        assertThat(confirm(second).statusCode()).isEqualTo("CONFIRMED");
    }

    @Test
    void courierRetainsBusinessDetailsThroughPersistenceConfirmationAndAnalyticsSource() {
        var original = command(UUID.randomUUID().toString(), null, "COURIER", "2026-10", "expense", null, "18.00");
        var courier = new HrExpenseDetails.Courier("合作合同原件", "合作门店", "顺丰速运", "000012345");
        var data = new HrExpenseDetails("expense", "寄送双方盖章合同", "张三", null, null, null,
                null, null, null, null, null, null, null, "INVOICE-18", null, null, null, courier);
        var c = new HrExpenseCommand(original.requestId(), null, "寄送合作合同", "COURIER",
                original.employeeCode(), original.cityCode(), original.cityName(), original.costDate(),
                original.amount(), "UNPAID", null, null, original.costMonth(), data);
        var row = save(null, HrExpenseRules.classify(c));
        assertThat(save(null, c).id()).isEqualTo(row.id());
        assertThat(row.data().expense().courier()).isEqualTo(courier);
        var confirmed = confirm(row);
        assertThat(confirmed.data().expense().courier()).isEqualTo(courier);
        assertThat(list().confirmedAmount()).isEqualByComparingTo("18.00");
        assertThat(source.page("T1", "").items().getFirst().get("data_json"))
                .contains("COURIER", "合作合同原件", "合作门店", "顺丰速运", "000012345");
        assertThat(source.page("OTHER", "").items()).isEmpty();
    }

    @Test
    void travelStaysInExpenseAndMonthCannotBeShiftedByProcessingDate() {
        var valid =
                command(
                        UUID.randomUUID().toString(),
                        null,
                        "TRAVEL",
                        "2026-10",
                        "expense",
                        null,
                        "300.00");
        HrExpenseRules.validate(valid, true, 0);
        assertThatThrownBy(
                        () ->
                                save(
                                        null,
                                        command(
                                                UUID.randomUUID().toString(),
                                                null,
                                                "TRAVEL",
                                                "2026-10",
                                                "marketing",
                                                null,
                                                "300")))
                .hasMessageContaining("统一在报销");
        assertThatThrownBy(
                        () ->
                                save(
                                        null,
                                        command(
                                                UUID.randomUUID().toString(),
                                                null,
                                                "TRAVEL",
                                                "2026-09",
                                                "expense",
                                                null,
                                                "300")))
                .hasMessageContaining("实际发生月份");
        var monthly =
                save(
                        null,
                        command(
                                UUID.randomUUID().toString(),
                                null,
                                "NETWORK",
                                "2026-09",
                                "office",
                                null,
                                "300"));
        assertThat(monthly.data().costMonth()).isEqualTo("2026-09");
    }
}
