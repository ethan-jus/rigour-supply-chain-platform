package com.rigour.analytics.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.rigour.analytics.infrastructure.persistence.mapper.SupplyDashboardQueryMapper;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;

/** 使用正式迁移建表并执行回款补齐校验 SQL；仅连接隔离的 H2 内存库。 */
public class SupplyDashboardPaymentBackfillRepositoryTest {
    private static final String TENANT = "019fb700-0000-7000-8000-000000000001";
    private static final String OTHER_TENANT = "019fb700-0000-7000-8000-000000000099";
    private SqlSession session;
    private JdbcTemplate jdbc;
    private SupplyDashboardQueryMapper mapper;
    private MybatisPlusSupplyDashboardRepository repository;

    @BeforeEach
    void setUp() {
        var dataSource =
                new UnpooledDataSource(
                        "org.h2.Driver",
                        "jdbc:h2:mem:payment_backfill_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                        "sa",
                        "");
        var configuration =
                new Configuration(
                        new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(SupplyDashboardQueryMapper.class);
        session = new SqlSessionFactoryBuilder().build(configuration).openSession();
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(session.getConnection(), true));
        ScriptUtils.executeSqlScript(
                session.getConnection(),
                new ClassPathResource("db/migration/V2__bi_supply_dashboard_facts_and_etl.sql"));
        // H2 不提供这两个 MySQL 函数；保持 Mapper SQL 原样执行，仅适配函数实现。
        jdbc.execute("CREATE ALIAS CRC32 FOR '" + getClass().getName() + ".crc32'");
        jdbc.execute("CREATE ALIAS FORMAT FOR '" + getClass().getName() + ".format'");

        jdbc.execute(
                """
                CREATE TABLE bi_source_order_order_payment_record (
                    tenant_id VARCHAR(64), id BIGINT, order_id BIGINT, customer_id BIGINT,
                    collector_staff_code VARCHAR(50), payment_method_code VARCHAR(64),
                    paid_amount DECIMAL(24,6), deleted INT DEFAULT 0)
                """);
        jdbc.execute(
                """
                CREATE TABLE bi_source_order_order_sales_order (
                    tenant_id VARCHAR(64), id BIGINT, customer_id BIGINT,
                    region_code VARCHAR(64), owner_employee_code VARCHAR(50))
                """);
        jdbc.execute(
                """
CREATE TABLE bi_source_crm_crm_customer (
    tenant_id VARCHAR(64), id BIGINT, region_code VARCHAR(64), owner_employee_code VARCHAR(50))
""");
        mapper = session.getMapper(SupplyDashboardQueryMapper.class);
        repository = new MybatisPlusSupplyDashboardRepository(mapper);
    }

    @AfterEach
    void tearDown() {
        if (session != null) session.close();
    }

    @Test
    void healthQueryUsesMigratedPaymentIdAndExcludesOtherTenantsAndDeletedRows() {
        fact(TENANT, 101, "S1", "C1", "100.250000", 0);
        fact(TENANT, 102, "S2", "C1", "25.125000", 0);
        fact(OTHER_TENANT, 101, "OTHER", "OTHER", "9000", 0);
        fact(TENANT, 103, "DELETED", "DELETED", "9000", 1);

        Map<String, Object> health = mapper.biSalesPaymentFactBackfillHealth(TENANT);
        assertThat(decimal(health, "rowCount")).isEqualByComparingTo("2");
        assertThat(decimal(health, "amount")).isEqualByComparingTo("125.375000");
        assertThat(decimal(health, "regionCount")).isEqualByComparingTo("1");
        assertThat(decimal(health, "ownerCount")).isEqualByComparingTo("2");
        long signature =
                crc32("101|10|BJ|S1|C1|20|100.250000|BANK")
                        + crc32("102|10|BJ|S2|C1|20|25.125000|BANK");
        assertThat(decimal(health, "rowSignature"))
                .isEqualByComparingTo(BigDecimal.valueOf(signature));
    }

    @Test
    void emptyTenantReturnsZeroHealthInsteadOfFailing() {
        Map<String, Object> health = mapper.biSalesPaymentFactBackfillHealth(TENANT);
        for (String key :
                new String[] {"rowCount", "amount", "regionCount", "ownerCount", "rowSignature"}) {
            assertThat(decimal(health, key)).isZero();
        }
    }

    @ParameterizedTest
    @CsvSource(
            value = {
                "ORDER_OWNER,CUSTOMER_OWNER,COLLECTOR,ORDER_OWNER",
                "'',CUSTOMER_OWNER,COLLECTOR,NULL",
                "NULL,'',COLLECTOR,NULL",
                "NULL,NULL,NULL,NULL"
            },
            nullValues = "NULL")
    void matchingPaymentProjectionDoesNotTriggerRepeatedBackfill(
            String orderOwner, String customerOwner, String collector, String projectedOwner) {
        jdbc.update(
                "INSERT INTO bi_source_order_order_sales_order VALUES (?, 10, 20, 'BJ', ?)",
                TENANT,
                orderOwner);
        jdbc.update(
                "INSERT INTO bi_source_crm_crm_customer VALUES (?, 20, 'SH', ?)",
                TENANT,
                customerOwner);
        jdbc.update(
                "INSERT INTO bi_source_order_order_payment_record VALUES (?, 101, 10, 20, ?,"
                    + " 'BANK', 125.375, 0)",
                TENANT,
                collector);
        fact(TENANT, 101, projectedOwner, collector, "125.375000", 0);

        assertThat(repository.refreshTargetNeedsBackfill(TENANT, "ORDER_PAYMENT_RECORD")).isFalse();

        // 金额和行数相同，支付来源 ID 错配仍应触发补齐，不能用 BI 自增主键代替来源 ID。
        jdbc.update(
                "UPDATE bi_sales_payment_fact SET payment_id = 999 WHERE tenant_id = ?", TENANT);
        session.clearCache();
        assertThat(repository.refreshTargetNeedsBackfill(TENANT, "ORDER_PAYMENT_RECORD")).isTrue();
    }

    private void fact(
            String tenant,
            long paymentId,
            String owner,
            String collector,
            String amount,
            int deleted) {
        jdbc.update(
                """
INSERT INTO bi_sales_payment_fact (
    tenant_id, payment_id, order_id, customer_id, region_code, owner_staff_code,
    collector_staff_code, payment_method_code, paid_amount, payment_time, synced_time, deleted
) VALUES (?, ?, 10, 20, 'BJ', ?, ?, 'BANK', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?)
""",
                tenant,
                paymentId,
                owner,
                collector,
                new BigDecimal(amount),
                deleted);
    }

    private static BigDecimal decimal(Map<String, Object> values, String key) {
        Object value =
                values.entrySet().stream()
                        .filter(entry -> entry.getKey().equalsIgnoreCase(key))
                        .findFirst()
                        .orElseThrow()
                        .getValue();
        return new BigDecimal(value.toString());
    }

    public static long crc32(String value) {
        var checksum = new CRC32();
        checksum.update(value.getBytes(StandardCharsets.UTF_8));
        return checksum.getValue();
    }

    public static String format(BigDecimal value, int places) {
        var formatter = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.US));
        formatter.setMinimumFractionDigits(places);
        formatter.setMaximumFractionDigits(places);
        formatter.setRoundingMode(RoundingMode.HALF_UP);
        return formatter.format(value);
    }
}
