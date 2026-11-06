package com.rigour.erp.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rigour.erp.api.v1.model.*;
import com.rigour.erp.application.port.out.ErpStockInOrderStore;
import com.rigour.erp.application.service.inventory.ErpStockInOrderService;
import com.rigour.erp.application.service.supply.ErpProcurementOrderService;
import com.rigour.erp.infrastructure.persistence.mapper.*;
import com.rigour.shared.context.*;
import com.rigour.shared.core.exception.BusinessException;

import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.interceptor.*;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/** 实际执行MyBatis语句验证业务闭环。H2适配语法不等于MySQL迁移验收。 */
class ProcurementPersistenceTest {
    SingleConnectionDataSource ds;
    JdbcTemplate jdbc;
    MybatisPlusProcurementOrderRepository orders;
    MybatisPlusSupplierProfileRepository supplierProfiles;
    ErpProcurementOrderService service;
    ErpStockInOrderService receipts;
    ProcurementReferenceMapper references;
    ErpWarehouseDataScope scopes;
    String tenant = UUID.randomUUID().toString();
    Clock clock = Clock.fixed(Instant.parse("2026-11-02T02:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setup() throws Exception {
        ds =
                new SingleConnectionDataSource(
                        "jdbc:h2:mem:purchase_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                        "sa",
                        "",
                        true);
        jdbc = new JdbcTemplate(ds);
        String baseline =
                Files.readString(
                        Path.of(
                                "src/main/resources/db/migration/V19__erp_internal_product_inventory_baseline.sql"));
        var creates = Pattern.compile("CREATE TABLE .*?;", Pattern.DOTALL).matcher(baseline);
        while (creates.find())
            jdbc.execute(
                    creates.group()
                            .replaceAll(
                                    "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
                                        + " COLLATE=utf8mb4_0900_ai_ci",
                                    ""));
        for (String version :
                List.of(
                        "V20__", "V27__", "V29__", "V30__", "V32__", "V34__", "V35__", "V36__",
                        "V41__", "V42__", "V44__")) {
            Path file;
            try (var files = Files.list(Path.of("src/main/resources/db/migration"))) {
                file =
                        files.filter(p -> p.getFileName().toString().startsWith(version))
                                .findFirst()
                                .orElseThrow();
            }
            String sql = Files.readString(file).replaceAll("(?m)^--.*$", "");
            var alterations =
                    Pattern.compile("ALTER TABLE (erp_[a-z_]+)\\s+(.*?);", Pattern.DOTALL)
                            .matcher(sql);
            while (alterations.find()) {
                String table = alterations.group(1);
                if (!Set.of(
                                "erp_product",
                                "erp_product_variant",
                                "erp_supplier_profile",
                                "erp_inventory_warehouse",
                                "erp_procurement_order",
                                "erp_procurement_order_line",
                                "erp_stock_in_order",
                                "erp_stock_in_order_line",
                                "erp_stock_balance",
                                "erp_stock_flow")
                        .contains(table)) continue;
                for (String change : alterations.group(2).split(",\\s*(?=ADD |MODIFY )")) {
                    if (change.strip().startsWith("ADD KEY")
                            || change.strip().startsWith("ADD UNIQUE KEY")) continue;
                    change =
                            change.replaceAll("\\s+COLLATE utf8mb4_general_ci", "")
                                    .replaceAll("\\s+CHARACTER SET utf8mb4", "")
                                    .replaceAll("\\s+AFTER [a-z_]+", "")
                                    .replace("MODIFY COLUMN", "ALTER COLUMN");
                    jdbc.execute("ALTER TABLE " + table + " " + change);
                }
            }
        }
        jdbc.update(
                "INSERT INTO"
                    + " erp_supplier_profile(id,tenant_id,supplier_code,supplier_name,status_code)"
                    + " VALUES (1,?,'SUP-1','供应商','ACTIVE')",
                tenant);
        jdbc.update(
                "INSERT INTO"
                    + " erp_inventory_warehouse(id,tenant_id,warehouse_code,warehouse_name,address,contact_name,contact_phone,status_code)"
                    + " VALUES (2,?,'WH-1','仓库','收货地址','张三','13800000000','ACTIVE')",
                tenant);
        jdbc.update(
                "INSERT INTO"
                    + " erp_product(id,tenant_id,product_code,product_name,unit_code,big_unit_code,base_to_big_rate,submit_status_code)"
                    + " VALUES (3,?,'PRD-1','饮料','BOTTLE','BOX',24,'SUBMITTED')",
                tenant);
        jdbc.update(
                "INSERT INTO"
                    + " erp_product_variant(id,tenant_id,product_id,variant_code,unit_code,purchase_price)"
                    + " VALUES (4,?,3,'SKU-1','BOTTLE',2)",
                tenant);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        var types =
                List.of(
                        InternalSupplierProfileMapper.class,
                        InternalInventoryWarehouseMapper.class,
                        InternalProductMapper.class,
                        InternalProductVariantMapper.class,
                        InternalProcurementOrderMapper.class,
                        InternalProcurementOrderLineMapper.class,
                        ProcurementReferenceMapper.class,
                        InternalStockInOrderMapper.class,
                        InternalStockInOrderLineMapper.class,
                        InternalStockBalanceMapper.class,
                        InternalStockFlowMapper.class);
        types.forEach(config::addMapper);
        config.addInterceptor(new H2Collation());
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        var suppliers = session.getMapper(InternalSupplierProfileMapper.class);
        supplierProfiles = transactional(new MybatisPlusSupplierProfileRepository(suppliers, clock));
        var warehouses = session.getMapper(InternalInventoryWarehouseMapper.class);
        var products = session.getMapper(InternalProductMapper.class);
        var variants = session.getMapper(InternalProductVariantMapper.class);
        var heads = session.getMapper(InternalProcurementOrderMapper.class);
        var lines = session.getMapper(InternalProcurementOrderLineMapper.class);
        references = session.getMapper(ProcurementReferenceMapper.class);
        var read =
                new ProcurementReadModel(
                        suppliers, warehouses, products, variants, lines, references);
        scopes = mock(ErpWarehouseDataScope.class);
        when(scopes.visibleDocument(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.<String[]>any()))
                .thenReturn(true);
        when(scopes.apply(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.<String[]>any()))
                .thenAnswer(inv -> inv.getArgument(0));
        orders =
                transactional(
                        new MybatisPlusProcurementOrderRepository(
                                heads,
                                lines,
                                suppliers,
                                warehouses,
                                products,
                                variants,
                                clock,
                                scopes,
                                read,
                                references));
        service = new ErpProcurementOrderService(orders, clock);
        var stock =
                transactional(
                        new MybatisPlusStockInOrderRepository(
                                session.getMapper(InternalStockInOrderMapper.class),
                                session.getMapper(InternalStockInOrderLineMapper.class),
                                heads,
                                lines,
                                session.getMapper(InternalStockBalanceMapper.class),
                                session.getMapper(InternalStockFlowMapper.class),
                                suppliers,
                                warehouses,
                                clock,
                                scopes,
                                read));
        receipts = new ErpStockInOrderService(stock);
        UUID user = UUID.randomUUID();
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        user,
                        UUID.fromString(tenant),
                        user,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of("erp"),
                        Set.of("erp:supply:read", "erp:supply:write")));
    }

    @org.apache.ibatis.plugin.Intercepts(
            @org.apache.ibatis.plugin.Signature(
                    type = org.apache.ibatis.executor.statement.StatementHandler.class,
                    method = "prepare",
                    args = {java.sql.Connection.class, Integer.class}))
    public static class H2Collation implements org.apache.ibatis.plugin.Interceptor {
        public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
            var sql =
                    ((org.apache.ibatis.executor.statement.StatementHandler) invocation.getTarget())
                            .getBoundSql();
            org.apache.ibatis.reflection.SystemMetaObject.forObject(sql)
                    .setValue("sql", sql.getSql().replace(" COLLATE utf8mb4_general_ci", ""));
            return invocation.proceed();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T transactional(T target) {
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(
                new TransactionInterceptor(
                        new DataSourceTransactionManager(ds),
                        new MatchAlwaysTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    @AfterEach
    void close() {
        TestAuthorizationContext.clear();
        ds.destroy();
    }

    InternalProcurementOrderCommand command(BigDecimal price) {
        return new InternalProcurementOrderCommand(
                true,
                null,
                null,
                null,
                List.of(
                        new InternalProcurementOrderLineCommand(
                                null, null, BigDecimal.ONE, price, null, "PRD-1", "SKU-1", "BOX")),
                null,
                null,
                "SUP-1",
                "WH-1",
                LocalDate.of(2026, 11, 2),
                new BigDecimal("8.00"),
                "BUYER",
                null,
                null,
                null,
                null,
                null);
    }

    @Test
    void supplierFinancialFieldsPersistOnCreateUpdateAndClear() {
        var command = new InternalSupplierProfileCommand("财务档案", "联系人", "027-12345678",
                "联系地址", "开户银行", "6222000012345678", "ACTIVE", null, 0,
                "开户名称", "开票抬头", "EXAMPLE-TAX-001");
        var created = supplierProfiles.create(tenant, "SUP-FINANCE", command, "actor");
        assertThat(created.accountName()).isEqualTo("开户名称");
        assertThat(created.invoiceTitle()).isEqualTo("开票抬头");
        assertThat(created.taxpayerNumber()).isEqualTo("EXAMPLE-TAX-001");
        var listed = supplierProfiles.suppliers(tenant, 0, 20,
                new com.rigour.erp.application.port.out.ErpSupplierProfileStore.SupplierSearchCriteria(
                        "SUP-FINANCE", null, null, null));
        assertThat(listed.items()).singleElement().extracting(InternalSupplierProfileView::invoiceTitle)
                .isEqualTo("开票抬头");
        var updated = supplierProfiles.update(tenant, created.id(), new InternalSupplierProfileCommand(
                "财务档案", "联系人", "027-12345678", "联系地址", "开户银行", "6222000012345678",
                "ACTIVE", null, created.revision(), "开户新名称", "开票新抬头", null), "actor");
        assertThat(updated.supplierCode()).isEqualTo("SUP-FINANCE");
        assertThat(updated.accountName()).isEqualTo("开户新名称");
        assertThat(updated.invoiceTitle()).isEqualTo("开票新抬头");
        assertThat(updated.taxpayerNumber()).isNull();
        assertThat(supplierProfiles.supplier(UUID.randomUUID().toString(), created.id())).isEmpty();
    }

    @Test
    void procurementTotalsUseAllFilteredOrdersAndNeverAddDifferentUnits() {
        var first = service.create(command(new BigDecimal("2")));
        var second = service.create(command(new BigDecimal("3")));
        var third = service.create(command(new BigDecimal("4")));
        jdbc.update("UPDATE erp_procurement_order SET freight_bearer_code='SUPPLIER' WHERE id=?", second.id());
        jdbc.update("UPDATE erp_procurement_order SET freight_bearer_code='INCLUDED' WHERE id=?", third.id());
        jdbc.update("UPDATE erp_procurement_order_line SET unit_code='BOTTLE',quantity=5 WHERE tenant_id=? AND procurement_no=?", tenant, third.procurementNo());
        var deleted = service.create(command(new BigDecimal("100")));
        jdbc.update("UPDATE erp_procurement_order SET deleted=1 WHERE id=?", deleted.id());
        var foreign = service.create(command(new BigDecimal("1000")));
        jdbc.update("UPDATE erp_procurement_order SET tenant_id=? WHERE id=?", UUID.randomUUID().toString(), foreign.id());
        var criteria = new com.rigour.erp.application.port.out.ErpProcurementOrderStore.ProcurementOrderSearchCriteria(
                null, null, null, null, null, null);
        var page = orders.procurementOrders(tenant, 1, 1, criteria);
        assertThat(page.items()).hasSize(1);
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.totals().quantityByUnit()).containsOnlyKeys("BOX", "BOTTLE");
        assertThat(page.totals().quantityByUnit().get("BOX")).isEqualByComparingTo("2");
        assertThat(page.totals().quantityByUnit().get("BOTTLE")).isEqualByComparingTo("5");
        assertThat(page.totals().purchaseAmount()).isEqualByComparingTo("17");
        var filtered = orders.procurementOrders(tenant, 0, 20,
                new com.rigour.erp.application.port.out.ErpProcurementOrderStore.ProcurementOrderSearchCriteria(
                        first.procurementNo(), null, null, null, null, null));
        assertThat(filtered.total()).isEqualTo(1);
        assertThat(filtered.totals().purchaseAmount()).isEqualByComparingTo("10");
        var empty = orders.procurementOrders(tenant, 0, 20,
                new com.rigour.erp.application.port.out.ErpProcurementOrderStore.ProcurementOrderSearchCriteria(
                        "MISSING", null, null, null, null, null));
        assertThat(empty.totals().quantityByUnit()).isEmpty();
        assertThat(empty.totals().purchaseAmount()).isEqualByComparingTo("0");
        doAnswer(inv -> inv.getArgument(0, com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class)
                .apply("procurement_no = {0}", first.procurementNo()))
                .when(scopes).apply(any(), anyString(), org.mockito.ArgumentMatchers.<String[]>any());
        var restricted = orders.procurementOrders(tenant, 0, 20, criteria);
        assertThat(restricted.total()).isEqualTo(1);
        assertThat(restricted.totals().quantityByUnit().get("BOX")).isEqualByComparingTo("1");
        assertThat(restricted.totals().purchaseAmount()).isEqualByComparingTo("10");
    }

    @Test
    void savesOnlyCodesAndConfirmsExactlyOnce() {
        var order = service.create(command(BigDecimal.ZERO));
        assertThat(order.confirmationStatusCode()).isEqualTo("UNCONFIRMED");
        assertThat(order.statusCode()).isEqualTo("DRAFT");
        assertThat(order.receiverName()).isEqualTo("张三");
        assertThat(order.receivingAddress()).isEqualTo("收货地址");
        var head =
                jdbc.queryForMap(
                        "SELECT supplier_id,target_warehouse_id,supplier_code,target_warehouse_code"
                            + " FROM erp_procurement_order WHERE id=?",
                        order.id());
        assertThat(head.get("supplier_id")).isNull();
        assertThat(head.get("target_warehouse_id")).isNull();
        assertThat(head.get("supplier_code")).isEqualTo("SUP-1");
        var line =
                jdbc.queryForMap(
                        "SELECT"
                            + " procurement_order_id,product_id,product_variant_id,line_no,received_quantity,product_name_snapshot,conversion_rate"
                            + " FROM erp_procurement_order_line");
        for (String col :
                List.of(
                        "procurement_order_id",
                        "product_id",
                        "product_variant_id",
                        "line_no",
                        "received_quantity",
                        "product_name_snapshot")) assertThat(line.get(col)).as(col).isNull();
        assertThat((BigDecimal) line.get("conversion_rate")).isEqualByComparingTo("24");
        var confirmed = service.confirm(order.id(), order.revision());
        assertThat(confirmed.confirmationStatusCode()).isEqualTo("CONFIRMED");
        assertThatThrownBy(() -> service.confirm(order.id(), order.revision()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> orders.delete(tenant, order.id(), confirmed.revision(), "actor"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                orders.update(
                                        tenant,
                                        order.id(),
                                        new com.rigour.erp.application.port.out
                                                .ErpProcurementOrderStore.ProcurementOrderWrite(
                                                1L,
                                                2L,
                                                "DRAFT",
                                                null,
                                                BigDecimal.ONE,
                                                BigDecimal.ZERO,
                                                List.of(),
                                                null,
                                                confirmed.revision()),
                                        "actor"))
                .isInstanceOf(BusinessException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT deleted FROM erp_procurement_order WHERE id=?",
                                Integer.class,
                                order.id()))
                .isZero();
    }

    @Test
    void partialBaseUnitReceiptsDoNotRoundOrOverwritePurchaseLines() {
        var order = service.create(command(new BigDecimal("48")));
        order = service.confirm(order.id(), order.revision());
        Long id = order.id(), lineId = order.lines().getFirst().id();
        int revision = order.revision();
        receipts.confirmProcurementStockIn(
                new InternalProcurementStockInCommand(
                        id,
                        revision,
                        null,
                        List.of(
                                new InternalProcurementStockInLineCommand(
                                        lineId, BigDecimal.ONE, null, "BOTTLE")),
                        null));
        var after = service.procurementOrder(id);
        assertThat(after.statusCode()).isEqualTo("PARTIAL_IN");
        assertThat(after.confirmationStatusCode()).isEqualTo("CONFIRMED");
        assertThat(after.lines().getFirst().receivedBaseQuantity()).isEqualByComparingTo("1");
        assertThatThrownBy(
                        () ->
                                receipts.confirmProcurementStockIn(
                                        new InternalProcurementStockInCommand(
                                                id,
                                                revision,
                                                null,
                                                List.of(
                                                        new InternalProcurementStockInLineCommand(
                                                                lineId,
                                                                BigDecimal.ONE,
                                                                null,
                                                                "BOTTLE")),
                                                null)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                receipts.confirmProcurementStockIn(
                                        new InternalProcurementStockInCommand(
                                                id,
                                                after.revision(),
                                                null,
                                                List.of(
                                                        new InternalProcurementStockInLineCommand(
                                                                lineId,
                                                                new BigDecimal("24"),
                                                                null,
                                                                "BOTTLE")),
                                                null)))
                .isInstanceOf(BusinessException.class);
        receipts.confirmProcurementStockIn(
                new InternalProcurementStockInCommand(
                        id,
                        after.revision(),
                        null,
                        List.of(
                                new InternalProcurementStockInLineCommand(
                                        lineId, new BigDecimal("23"), null, "BOTTLE")),
                        null));
        var completed = service.procurementOrder(id);
        assertThat(completed.statusCode()).isEqualTo("COMPLETED");
        assertThat(completed.lines().getFirst().receivedQuantity()).isEqualByComparingTo("1");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT available_quantity FROM erp_stock_balance",
                                BigDecimal.class))
                .isEqualByComparingTo("24");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT SUM(quantity_delta) FROM erp_stock_flow", BigDecimal.class))
                .isEqualByComparingTo("24");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT SUM(amount) FROM erp_stock_in_order_line",
                                BigDecimal.class))
                .isEqualByComparingTo("48");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT received_quantity FROM erp_procurement_order_line",
                                BigDecimal.class))
                .isNull();
        assertThat(references.receipts(UUID.randomUUID().toString(), completed.procurementNo()))
                .isEmpty();
    }

    @Test
    void listQuantitiesGroupByPurchaseUnitAndExcludeDeletedOrForeignTenantLines() {
        jdbc.update("INSERT INTO erp_product_variant(id,tenant_id,product_id,variant_code,unit_code,purchase_price) VALUES (5,?,3,'SKU-2','BOTTLE',2)", tenant);
        jdbc.update("INSERT INTO erp_product_variant(id,tenant_id,product_id,variant_code,unit_code,purchase_price) VALUES (6,?,3,'SKU-3','BOTTLE',2)", tenant);
        var order = service.create(new InternalProcurementOrderCommand(
                false, null, null, null,
                List.of(
                        new InternalProcurementOrderLineCommand(null, null, new BigDecimal("2"), BigDecimal.ONE, null, "PRD-1", "SKU-1", "BOX"),
                        new InternalProcurementOrderLineCommand(null, null, new BigDecimal("10"), BigDecimal.ONE, null, "PRD-1", "SKU-2", "BOTTLE"),
                        new InternalProcurementOrderLineCommand(null, null, new BigDecimal("3"), BigDecimal.ONE, null, "PRD-1", "SKU-3", "BOX")),
                null, null, "SUP-1", "WH-1", LocalDate.of(2026, 11, 2), BigDecimal.ZERO, "BUYER", null, null, null, null, null));
        var summary = service.procurementOrders(0, 20, order.procurementNo(), null, null, null, null, null).items().getFirst();
        assertThat(summary.totalQuantity()).isZero();
        assertThat(summary.lineCount()).isEqualTo(3);
        assertThat(summary.quantityByUnit().get("BOX")).isEqualByComparingTo("5");
        assertThat(summary.quantityByUnit().get("BOTTLE")).isEqualByComparingTo("10");
        jdbc.update("UPDATE erp_procurement_order_line SET deleted=1 WHERE variant_code='SKU-3'");
        jdbc.update("UPDATE erp_procurement_order_line SET tenant_id=? WHERE variant_code='SKU-2'", UUID.randomUUID().toString());
        var filtered = service.procurementOrders(0, 20, order.procurementNo(), null, null, null, null, null).items().getFirst();
        assertThat(filtered.lineCount()).isEqualTo(1);
        assertThat(filtered.quantityByUnit()).hasSize(1);
        assertThat(filtered.quantityByUnit().get("BOX")).isEqualByComparingTo("2");
    }

    @Test
    void listFiltersAndWarehouseScopeUseTenantAndCodes() {
        var order = service.create(command(BigDecimal.ONE));
        var found =
                service.procurementOrders(
                        0,
                        20,
                        order.procurementNo(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        "SUP-1",
                        "WH-1",
                        "UNCONFIRMED",
                        LocalDate.of(2026, 11, 2),
                        LocalDate.of(2026, 11, 2));
        assertThat(found.total()).isEqualTo(1);
        assertThat(found.items().getFirst().createdBy()).isNotBlank();
        assertThat(found.items().getFirst().quantityByUnit().get("BOX")).isEqualByComparingTo("1");
        assertThat(
                        service.procurementOrders(
                                        0,
                                        20,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        "SUP-1",
                                        "WH-1",
                                        "CONFIRMED",
                                        null,
                                        null)
                                .total())
                .isZero();
        var auth = mock(com.rigour.tenant.iam.client.SupplyAuthorizationClient.class);
        var all =
                new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.Limit(
                        "ALL", List.of());
        var warehouses =
                new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.Limit(
                        "SPECIFIED", List.of("2"));
        var clause =
                new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.Clause(
                        UUID.randomUUID(), "INVENTORY", "WAREHOUSE", all, all, warehouses, false);
        String action = "erp:procurement:read";
        when(auth.authorization(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(
                        new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView(
                                "ACTIVE",
                                UUID.fromString(tenant),
                                UUID.randomUUID(),
                                "E1",
                                1,
                                1,
                                1,
                                1,
                                Set.of(action),
                                action,
                                true,
                                List.of(clause),
                                all,
                                all));
        var scope = new ErpWarehouseDataScope(auth, mock(ErpWarehouseScopeMapper.class));
        var predicate = scope.predicate(action, "target_warehouse_id");
        var args = new ArrayList<Object>();
        args.add(tenant);
        args.addAll(predicate.args());
        String sql =
                "SELECT COUNT(*) FROM erp_procurement_order WHERE tenant_id=? AND "
                        + predicate.sql().replace(" COLLATE utf8mb4_general_ci", "");
        assertThat(jdbc.queryForObject(sql, Integer.class, args.toArray())).isEqualTo(1);
        // 同编码的异租户档案不能替代本租户仓库。
        jdbc.update(
                "UPDATE erp_inventory_warehouse SET tenant_id=? WHERE id=2",
                UUID.randomUUID().toString());
        assertThat(jdbc.queryForObject(sql, Integer.class, args.toArray())).isZero();
    }

    @Test
    void productPickerCombinesIndependentFiltersInsteadOfSearchingOtherFields() {
        jdbc.update(
                "INSERT INTO"
                    + " erp_product_variant(id,tenant_id,product_id,variant_code,unit_code,purchase_price)"
                    + " VALUES(5,?,3,'SKU-2','BOTTLE',3)",
                tenant);
        assertThat(service.procurementProducts(0, 20, "饮料", "PRD-1", "SKU-1").items())
                .extracting(InternalProcurementProductView::variantCode)
                .containsExactly("SKU-1");
        assertThat(service.procurementProducts(0, 20, "PRD-1", null, null).total()).isZero();
        assertThat(service.procurementProducts(0, 20, null, "饮料", null).total()).isZero();
        assertThat(service.procurementProducts(0, 20, "饮料", "OTHER", "SKU-1").total()).isZero();
        assertThat(
                        orders.procurementProducts(
                                        UUID.randomUUID().toString(), 0, 20, null, null, null)
                                .total())
                .isZero();
    }

    @Test
    void missingPriceAndForeignTenantReferencesAreRejected() {
        assertThatThrownBy(() -> service.create(command(null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("单价");
        assertThat(orders.supplierReference(UUID.randomUUID().toString(), "SUP-1", null)).isEmpty();
        assertThat(orders.productVariantByCode(UUID.randomUUID().toString(), "PRD-1", "SKU-1"))
                .isEmpty();
        assertThat(orders.productVariantByCode(tenant, "OTHER-PRODUCT", "SKU-1")).isEmpty();
    }
}
