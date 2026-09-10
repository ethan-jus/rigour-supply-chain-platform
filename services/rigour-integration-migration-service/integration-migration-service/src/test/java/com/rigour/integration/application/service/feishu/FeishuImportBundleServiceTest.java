package com.rigour.integration.application.service.feishu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.erp.api.v1.model.ExternalProductResolveRowCommand;
import com.rigour.erp.api.v1.model.ExternalProductResolvedView;
import com.rigour.erp.api.v1.model.ExternalProductRowCommand;
import com.rigour.erp.api.v1.model.ExternalProductSyncResult;
import com.rigour.erp.api.v1.model.ExternalProductSyncRowResult;
import com.rigour.hr.api.v1.model.ExternalEmployeeRowCommand;
import com.rigour.hr.api.v1.model.ExternalEmployeeResolveCommand;
import com.rigour.hr.api.v1.model.ExternalEmployeeResolvedView;
import com.rigour.hr.api.v1.model.ExternalEmployeeSyncResult;
import com.rigour.hr.api.v1.model.ExternalEmployeeSyncRowResult;
import com.rigour.integration.api.v1.model.FeishuImportModels.FeishuImportPreflightResult;
import com.rigour.integration.api.v1.model.FeishuImportModels.FeishuImportRunCommand;
import com.rigour.integration.api.v1.model.FeishuImportModels.FeishuImportRunResult;
import com.rigour.integration.application.port.out.CrmCustomerProjectionClient;
import com.rigour.integration.application.port.out.ErpProductProjectionClient;
import com.rigour.integration.application.port.out.FeishuBitableClient;
import com.rigour.integration.application.port.out.FeishuBitableClient.BitableField;
import com.rigour.integration.application.port.out.FeishuBitableClient.BitableRecord;
import com.rigour.integration.application.port.out.FeishuBitableClient.BitableTable;
import com.rigour.integration.application.port.out.FeishuBitableClient.DownloadedAttachment;
import com.rigour.integration.application.port.out.FeishuImportStore;
import com.rigour.integration.application.port.out.FeishuImportStore.ExistingDeduplicationRow;
import com.rigour.integration.application.port.out.FeishuImportStore.PreflightBatch;
import com.rigour.integration.application.port.out.FeishuImportStore.PreflightRawRow;
import com.rigour.integration.application.port.out.FeishuImportStore.RowAttachmentUpdate;
import com.rigour.integration.application.port.out.FeishuImportStore.RowProjectionUpdate;
import com.rigour.integration.application.port.out.FeishuImportStore.StoredBatch;
import com.rigour.integration.application.port.out.FeishuImportStore.StoredRawRow;
import com.rigour.integration.application.port.out.HrEmployeeProjectionClient;
import com.rigour.integration.application.port.out.OrderSalesOrderProjectionClient;
import com.rigour.integration.application.port.out.ProductMediaStorage;
import com.rigour.integration.infrastructure.config.FeishuImportProperties;
import com.rigour.merchant.api.v1.model.ExternalCrmAreaRowCommand;
import com.rigour.merchant.api.v1.model.ExternalCrmAreaSyncResult;
import com.rigour.merchant.api.v1.model.ExternalCrmAreaSyncRowResult;
import com.rigour.merchant.api.v1.model.ExternalCrmCustomerRowCommand;
import com.rigour.merchant.api.v1.model.ExternalCrmCustomerSyncResult;
import com.rigour.merchant.api.v1.model.ExternalCrmCustomerSyncRowResult;
import com.rigour.order.api.v1.model.SalesOrderCommand;
import com.rigour.order.api.v1.model.SalesOrderDetailView;
import com.rigour.order.api.v1.model.SalesOrderLineView;
import com.rigour.order.api.v1.model.SalesPaymentRecordCommand;
import com.rigour.order.api.v1.model.SalesPaymentRecordDetailView;
import com.rigour.settings.client.BusinessDictionaryBatchClient;
import com.rigour.settings.client.BusinessDictionaryBatchClient.Observation;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

class FeishuImportBundleServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("01a05a08-9cf2-7525-8e5c-111111111111");
    private static final UUID USER_ID = UUID.fromString("01a05a08-9cf2-7525-8e5c-222222222222");
    private static final Instant NOW = Instant.parse("2026-09-02T03:00:00Z");

    @BeforeEach
    void resetRequestContext() {
        com.rigour.shared.context.RequestContext.clear();
    }

    @Test
    void preflightRecognizesSalesOrderSheetAndAttachmentGap() {
        CapturingStore store = new CapturingStore();
        FeishuImportBundleService service = service(store);
        MockMultipartFile file = xlsx("销售订单.xlsx", "📋销售订单表", List.of(
                List.of("订单编号", "创建时间", "付款凭证", "回款凭证"),
                List.of("XS.20260901.0001", "2026-09-01 10:00:00", "pay.png", ""),
                List.of("XS.20260901.0002", "2026-09-01 11:00:00", "", "receipt-a.pdf;receipt-b.png")));

        FeishuImportPreflightResult result = service.preflight(caller(), file,
                "https://mcn7tpu25x8w.feishu.cn/base/LUvobJ32Fa1dKesorBhc7VF8nye");

        assertThat(result.sourceSystem()).isEqualTo("FEISHU");
        assertThat(result.status()).isEqualTo("PREFLIGHTED_WITH_WARNINGS");
        assertThat(result.totalSheets()).isEqualTo(1);
        assertThat(result.totalRows()).isEqualTo(2);
        assertThat(result.attachmentReferenceCount()).isEqualTo(3);
        assertThat(result.tables()).singleElement().satisfies(table -> {
            assertThat(table.tableCode()).isEqualTo("FEISHU_SALES_ORDER");
            assertThat(table.domainCode()).isEqualTo("ORDER");
            assertThat(table.objectType()).isEqualTo("SALES_ORDER");
            assertThat(table.mappingStatus()).isEqualTo("READY");
            assertThat(table.attachmentFields()).containsExactly("付款凭证", "回款凭证");
            assertThat(table.sampleRows()).hasSize(2);
        });
        assertThat(result.issues()).anySatisfy(issue ->
                assertThat(issue).satisfies(value -> {
                    assertThat(value.issueType()).isEqualTo("FEISHU_ATTACHMENT_SOURCE_REQUIRED");
                    assertThat(value.issueCategory()).isEqualTo("ATTACHMENT");
                    assertThat(value.blocking()).isFalse();
                    assertThat(value.resolutionAction()).isEqualTo("COMPENSATE_ATTACHMENT");
                }));
        assertThat(store.saved).isNotNull();
        assertThat(store.saved.sourceSystem()).isEqualTo("FEISHU");
        assertThat(store.saved.tables()).hasSize(1);
        assertThat(store.saved.rawRows()).hasSize(2);
        assertThat(store.saved.rawRows())
                .extracting("sourceDocumentNo")
                .containsExactly("XS.20260901.0001", "XS.20260901.0002");
    }

    @Test
    void preflightCapturesCommonFeishuDocumentAndDateFields() {
        CapturingStore store = new CapturingStore();
        FeishuImportBundleService service = service(store);
        MockMultipartFile file = xlsx("回款记录.xlsx", "💴回款记录表", List.of(
                List.of("回款编号", "关联订单", "回款日期", "回款门店", "实际回款额"),
                List.of("HK202609010001", "DD202609010001-武汉门店", "2026/09/01", "武汉门店", "128.00")));

        FeishuImportPreflightResult result = service.preflight(caller(), file,
                "https://mcn7tpu25x8w.feishu.cn/base/LUvobJ32Fa1dKesorBhc7VF8nye");

        assertThat(result.totalRows()).isEqualTo(1);
        assertThat(result.status()).isEqualTo("PREFLIGHTED");
        assertThat(result.tables()).singleElement().satisfies(table -> {
            assertThat(table.tableCode()).isEqualTo("FEISHU_PAYMENT_RECORD");
            assertThat(table.domainCode()).isEqualTo("ORDER");
            assertThat(table.objectType()).isEqualTo("PAYMENT_RECORD");
            assertThat(table.mappingStatus()).isEqualTo("READY");
        });
        assertThat(result.issues()).noneSatisfy(issue ->
                assertThat(issue.issueType()).isEqualTo("FEISHU_FIELD_MAPPING_REQUIRED"));
        assertThat(store.saved.rawRows()).singleElement().satisfies(row -> {
            assertThat(row.sourceDocumentNo()).isEqualTo("HK202609010001");
            assertThat(row.sourceCreatedAt()).isEqualTo(Instant.parse("2026-08-31T16:00:00Z"));
        });
    }

    @Test
    void preflightRecognizesSalesStaffSheet() {
        CapturingStore store = new CapturingStore();
        FeishuImportBundleService service = service(store);
        MockMultipartFile file = xlsx("销售人员信息.xlsx", "👨‍💼渡江战役团队管理", List.of(
                List.of("销售姓名", "姓名", "岗位", "职位", "销售区域", "销售leader", "城市", "手机号",
                        "入职日期", "离职日期", "在职状态"),
                List.of("2026-06-24-李嘉豪", "李嘉豪", "销售", "城市总", "华北地区",
                        "张海龙", "西安", "", "46197", "", "在职")));

        FeishuImportPreflightResult result = service.preflight(caller(), file,
                "https://mcn7tpu25x8w.feishu.cn/base/LUvobJ32Fa1dKesorBhc7VF8nye");

        assertThat(result.status()).isEqualTo("PREFLIGHTED");
        assertThat(result.tables()).singleElement().satisfies(table -> {
            assertThat(table.tableCode()).isEqualTo("FEISHU_SALES_STAFF");
            assertThat(table.domainCode()).isEqualTo("HR");
            assertThat(table.objectType()).isEqualTo("EMPLOYEE");
            assertThat(table.mappingStatus()).isEqualTo("READY");
        });
        assertThat(store.saved.rawRows()).singleElement().satisfies(row -> {
            assertThat(row.sourceDocumentNo()).isEqualTo("2026-06-24-李嘉豪");
            assertThat(row.sourceCreatedAt()).isEqualTo(Instant.parse("2026-06-23T16:00:00Z"));
        });
    }

    @Test
    void preflightRecognizesCrmAndErpMasterSheets() {
        CapturingStore store = new CapturingStore();
        FeishuImportBundleService service = service(store);
        MockMultipartFile file = xlsx("飞书主数据.xlsx", List.of(
                new SheetSource("商家库", List.of(
                        List.of("商家编号", "商家名称", "商家来源", "商家类目", "所属地区", "市", "创建时间"),
                        List.of("M1001", "武汉商家", "飞书建档", "台球", "华中地区", "武汉", "2026-09-01 10:00:00"))),
                new SheetSource("门店信息库", List.of(
                        List.of("门店编码", "门店名称", "城市", "门店状态", "创建时间"),
                        List.of("SP1001", "武汉门店", "武汉", "营业中", "2026-09-01 10:00:00"))),
                new SheetSource("产品信息库", List.of(
                        List.of("产品编码", "产品名称", "业务线", "品牌", "行业", "品类", "定价", "规格", "状态", "创建时间"),
                        List.of("P1001", "酸辣粉", "零售业务", "瑞盖", "食品", "粉面", "18.50", "箱", "在售", "2026-09-01 10:00:00")))));

        FeishuImportPreflightResult result = service.preflight(caller(), file, null);

        assertThat(result.status()).isEqualTo("PREFLIGHTED");
        assertThat(result.tables()).extracting("tableCode")
                .containsExactly("FEISHU_CUSTOMER", "FEISHU_STORE", "FEISHU_PRODUCT");
        assertThat(result.tables()).extracting("mappingStatus")
                .containsExactly("READY", "READY", "READY");
        assertThat(result.tables()).extracting("domainCode")
                .containsExactly("CRM", "CRM", "ERP");
    }

    @Test
    void tableCatalogUsesSpecificSheetDefinitionBeforeGenericAlias() {
        FeishuImportTableCatalog.Match calculation = FeishuImportTableCatalog.match(
                "📦产品信息库测算表",
                List.of("产品名", "业务线", "品牌", "行业", "品类", "成本价", "定价"));
        assertThat(calculation.tableCode()).isEqualTo("FEISHU_PRODUCT_CALCULATION");
        assertThat(calculation.domainCode()).isEqualTo("ERP");
        assertThat(calculation.objectType()).isEqualTo("PRODUCT_CALCULATION");
        assertThat(calculation.mappingStatus()).isEqualTo("NEEDS_FIELD_MAPPING");
        assertThat(calculation.missingHeaders()).isEmpty();

        FeishuImportTableCatalog.Match visitedStore = FeishuImportTableCatalog.match(
                "拜访门店信息",
                List.of("概要", "城市", "属性", "名称", "营业状态", "联系人", "联系方式",
                        "经营类型", "意向业务", "创建时间"));
        assertThat(visitedStore.tableCode()).isEqualTo("FEISHU_VISITED_STORE");
        assertThat(visitedStore.domainCode()).isEqualTo("CRM");
        assertThat(visitedStore.objectType()).isEqualTo("VISITED_STORE");
        assertThat(visitedStore.mappingStatus()).isEqualTo("NEEDS_FIELD_MAPPING");
        assertThat(visitedStore.missingHeaders()).isEmpty();
    }

    @Test
    void preflightDoesNotArchiveOriginalFilePath() {
        CapturingStore store = new CapturingStore();
        FeishuImportProperties properties = new FeishuImportProperties();
        properties.setSampleRows(3);
        FeishuImportBundleService service = new FeishuImportBundleService(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, "DEFAULT"),
                        new AtomicReference<>()),
                properties, Clock.fixed(NOW, ZoneOffset.UTC));
        MockMultipartFile file = xlsx("销售订单.xlsx", "📋销售订单表", List.of(
                List.of("订单编号", "创建时间"),
                List.of("XS.20260901.0001", "2026-09-01 10:00:00")));

        FeishuImportPreflightResult result = service.preflight(caller(), file, null);

        assertThat(service.batches(caller(), 10)).singleElement().satisfies(batch -> {
            assertThat(batch.batchId()).isEqualTo(result.batchId());
            assertThat(batch.totalRows()).isEqualTo(1);
        });
    }

    @Test
    void runSkipsSalesOrderWhenCustomerNameIsMissing() {
        CapturingStore store = new CapturingStore();
        FeishuImportBundleService service = service(store);
        MockMultipartFile file = xlsx("销售订单.xlsx", "销售订单表", List.of(
                List.of("订单编号", "创建时间", "付款凭证"),
                List.of("XS.20260901.0001", "2026-09-01 10:00:00", "pay.png")));

        service.preflight(caller(), file,
                "https://mcn7tpu25x8w.feishu.cn/base/LUvobJ32Fa1dKesorBhc7VF8nye");
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.projectedRows()).isZero();
        assertThat(result.skippedRows()).isEqualTo(1);
        assertThat(result.waitingMappingRows()).isZero();
        assertThat(result.failedRows()).isZero();
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.projectionStatus()).isEqualTo("SKIPPED");
            assertThat(row.targetDomain()).isEqualTo("ORDER");
            assertThat(row.targetObjectType()).isEqualTo("SALES_ORDER");
            assertThat(row.message()).contains("脏数据");
        });
        assertThat(store.projectionUpdates).hasSize(1);
        assertThat(store.projectionUpdates.getFirst().errorCode())
                .isEqualTo("FEISHU_ORDER_DIRTY_BLANK_CUSTOMER");
        assertThat(store.batchStatus).isEqualTo("SUCCEEDED");
    }

    @Test
    void runCreatesSalesOrderWhenInternalMappingExists() {
        CapturingStore store = new CapturingStore();
        AtomicReference<SalesOrderCommand> captured = new AtomicReference<>();
        String sourceNo = "XS.20260901.0001";
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, sourceNo), captured));
        MockMultipartFile file = xlsx("销售订单.xlsx", "销售订单表", List.of(
                List.of("订单编号", "创建时间", "客户ID", "商品ID", "规格ID", "单位编码",
                        "数量", "单价", "门店", "订单产品", "销售"),
                List.of(sourceNo, "2026-09-01 10:00:00", "1001", "2001", "3001", "BOX",
                        "2", "18.50", "武汉门店", "鲜榨果汁", "张三")));

        service.preflight(caller(), file,
                "https://mcn7tpu25x8w.feishu.cn/base/LUvobJ32Fa1dKesorBhc7VF8nye");
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.projectedRows()).isEqualTo(1);
        assertThat(result.waitingMappingRows()).isZero();
        assertThat(result.failedRows()).isZero();
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.projectionStatus()).isEqualTo("PROJECTED");
            assertThat(row.targetId()).isEqualTo("9001");
            assertThat(row.message()).contains("已导入Order");
        });
        assertThat(captured.get()).satisfies(command -> {
            assertThat(command.sourceSystemCode()).isEqualTo("FEISHU");
            assertThat(command.sourceOrderNo()).isEqualTo(sourceNo);
            assertThat(command.customerId()).isEqualTo(1001L);
            assertThat(command.lines()).singleElement().satisfies(line -> {
                assertThat(line.productId()).isEqualTo(2001L);
                assertThat(line.productVariantId()).isEqualTo(3001L);
                assertThat(line.unitCode()).isEqualTo("BOX");
                assertThat(line.quantity()).isEqualByComparingTo("2");
                assertThat(line.unitPrice()).isEqualByComparingTo("18.50");
            });
        });
        assertThat(store.projectionUpdates).hasSize(1);
        assertThat(store.batchStatus).isEqualTo("SUCCEEDED");
    }

    @Test
    void runUsesSalesDateBeforeCreateTimeForSalesOrderDate() {
        CapturingStore store = new CapturingStore();
        AtomicReference<SalesOrderCommand> captured = new AtomicReference<>();
        String sourceNo = "DD202609013377";
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, sourceNo), captured));
        MockMultipartFile file = xlsx("销售订单.xlsx", "销售订单表", List.of(
                List.of("订单编号", "创建时间", "销售日期", "客户ID", "商品ID", "规格ID", "单位编码",
                        "数量", "单价", "门店", "订单产品", "销售"),
                List.of(sourceNo, "2026-09-01 10:00:00", "2026-08-28", "1001", "2001", "3001",
                        "BOX", "3", "78.00", "星耀台球俱乐部南翔镇", "油泼辣子拌面", "张三")));

        service.preflight(caller(), file,
                "https://mcn7tpu25x8w.feishu.cn/base/LUvobJ32Fa1dKesorBhc7VF8nye");
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.projectedRows()).isEqualTo(1);
        assertThat(captured.get().orderDate()).isEqualTo(Instant.parse("2026-08-28T00:00:00Z"));
    }

    @Test
    void runUsesExcelSerialSalesDateForSalesOrderDate() {
        CapturingStore store = new CapturingStore();
        AtomicReference<SalesOrderCommand> captured = new AtomicReference<>();
        String sourceNo = "DD202608232985";
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, sourceNo), captured));
        MockMultipartFile file = xlsx("销售订单.xlsx", "销售订单表", List.of(
                List.of("订单编号", "创建时间", "销售日期", "客户ID", "商品ID", "规格ID", "单位编码",
                        "数量", "单价", "门店", "订单产品", "销售"),
                List.of(sourceNo, "2026-08-23 10:00:00", "46256", "1001", "2001", "3001",
                        "BOX", "6", "363.00", "悦·四季台球棋牌俱乐部（鸿基路店）", "油泼辣子拌面", "林逸民")));

        service.preflight(caller(), file,
                "https://mcn7tpu25x8w.feishu.cn/base/LUvobJ32Fa1dKesorBhc7VF8nye");
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.projectedRows()).isEqualTo(1);
        assertThat(captured.get().orderDate()).isEqualTo(Instant.parse("2026-08-22T00:00:00Z"));
    }

    @Test
    void runCreatesReviewSalesOrderWhenValidHeaderHasNoOrderLines() {
        CapturingStore store = new CapturingStore();
        AtomicReference<SalesOrderCommand> captured = new AtomicReference<>();
        String sourceNo = "DD202606021009";
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, sourceNo), captured));
        MockMultipartFile file = xlsx("销售订单.xlsx", "销售订单表", List.of(
                List.of("订单编号", "创建时间", "客户ID", "门店", "销售", "备注"),
                List.of(sourceNo, "2026-06-02 10:00:00", "1001", "喜刻台球", "张三",
                        "五一后发货，问题订单")));

        service.preflight(caller(), file,
                "https://mcn7tpu25x8w.feishu.cn/base/LUvobJ32Fa1dKesorBhc7VF8nye");
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.projectedRows()).isEqualTo(1);
        assertThat(result.waitingMappingRows()).isZero();
        assertThat(result.failedRows()).isZero();
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.projectionStatus()).isEqualTo("PROJECTED");
            assertThat(row.message()).contains("待完善订单");
        });
        assertThat(captured.get()).satisfies(command -> {
            assertThat(command.sourceOrderNo()).isEqualTo(sourceNo);
            assertThat(command.customerNameSnapshot()).isEqualTo("喜刻台球");
            assertThat(command.lines()).isEmpty();
        });
    }

    @Test
    void runKeepsOrderSubtotalWhenSalesOrderLineNeedsManualCompletion() {
        CapturingStore store = new CapturingStore();
        AtomicReference<SalesOrderCommand> captured = new AtomicReference<>();
        String sourceNo = "DD202608293292";
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, sourceNo), captured));
        MockMultipartFile file = xlsx("销售订单.xlsx", "销售订单表", List.of(
                List.of("订单编号", "创建时间", "客户ID", "门店", "订单产品", "数量", "实际小计", "销售"),
                List.of(sourceNo, "2026-08-29 10:00:00", "1001", "武汉门店",
                        "油泼辣子拌面，金汤肥牛", "6", "468.00", "张三")));

        service.preflight(caller(), file,
                "https://mcn7tpu25x8w.feishu.cn/base/LUvobJ32Fa1dKesorBhc7VF8nye");
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.projectedRows()).isEqualTo(1);
        assertThat(result.waitingMappingRows()).isZero();
        assertThat(captured.get()).satisfies(command -> {
            assertThat(command.sourceOrderNo()).isEqualTo(sourceNo);
            assertThat(command.lines()).singleElement().satisfies(line -> {
                assertThat(line.productId()).isNull();
                assertThat(line.productVariantId()).isNull();
                assertThat(line.productNameSnapshot()).isEqualTo("油泼辣子拌面，金汤肥牛");
                assertThat(line.quantity()).isEqualByComparingTo("6");
                assertThat(line.unitPrice()).isEqualByComparingTo("78.000000");
                assertThat(line.remark()).contains("缺少销售订单明细落库必填字段");
            });
        });
    }

    @Test
    void runCreatesDraftOrderWhenCustomerBlankButRecoverableSubtotalExists() {
        CapturingStore store = new CapturingStore();
        AtomicReference<SalesOrderCommand> captured = new AtomicReference<>();
        String sourceNo = "DD202608293292";
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, sourceNo), captured));
        MockMultipartFile file = xlsx("销售订单.xlsx", "销售订单表", List.of(
                List.of("订单编号门店", "订单编号", "创建时间", "订单产品", "数量", "实际小计", "付款凭证"),
                List.of(sourceNo + "-", sourceNo, "2026-08-29 10:00:00",
                        "油泼辣子拌面,金汤肥牛", "6", "468.00",
                        "tenant/feishu-attachments/FEISHU_SALES_ORDER/DD202608293292/付款凭证/hash.jpg")));

        service.preflight(caller(), file,
                "https://mcn7tpu25x8w.feishu.cn/base/LUvobJ32Fa1dKesorBhc7VF8nye");
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.projectedRows()).isEqualTo(1);
        assertThat(captured.get()).satisfies(command -> {
            assertThat(command.sourceOrderNo()).isEqualTo(sourceNo);
            assertThat(command.customerNameSnapshot()).isNull();
            assertThat(command.lines()).singleElement().satisfies(line -> {
                assertThat(line.productNameSnapshot()).isEqualTo("油泼辣子拌面,金汤肥牛");
                assertThat(line.quantity()).isEqualByComparingTo("6");
                assertThat(line.unitPrice()).isEqualByComparingTo("78.000000");
            });
        });
    }

    @Test
    void runSkipsSalesOrderWhenStoreNameIsBlankDirtyData() {
        CapturingStore store = new CapturingStore();
        AtomicReference<SalesOrderCommand> captured = new AtomicReference<>();
        String sourceNo = "DD202609033391";
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, sourceNo), captured));
        MockMultipartFile file = xlsx("销售订单.xlsx", "销售订单表", List.of(
                List.of("订单编号门店", "订单编号", "创建时间", "销售"),
                List.of(sourceNo + "-", sourceNo, "2026-09-03 10:00:00", "张三")));

        service.preflight(caller(), file,
                "https://mcn7tpu25x8w.feishu.cn/base/LUvobJ32Fa1dKesorBhc7VF8nye");
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.projectedRows()).isZero();
        assertThat(result.skippedRows()).isEqualTo(1);
        assertThat(result.waitingMappingRows()).isZero();
        assertThat(result.failedRows()).isZero();
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.projectionStatus()).isEqualTo("SKIPPED");
            assertThat(row.message()).contains("脏数据");
        });
        assertThat(captured.get()).isNull();
    }

    @Test
    void runCreatesSalesPaymentFromSalesOrderPaymentFields() {
        CapturingStore store = new CapturingStore();
        AtomicReference<SalesOrderCommand> capturedOrder = new AtomicReference<>();
        AtomicReference<SalesPaymentRecordCommand> capturedPayment = new AtomicReference<>();
        String sourceNo = "XS.20260901.0001";
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, sourceNo),
                        capturedOrder, Optional.empty(), capturedPayment));
        MockMultipartFile file = xlsx("销售订单.xlsx", "销售订单表", List.of(
                List.of("订单编号", "创建时间", "客户ID", "商品ID", "规格ID", "单位编码",
                        "数量", "单价", "门店", "订单产品", "销售",
                        "回款日期", "收款合计", "付款方式", "回款凭证"),
                List.of(sourceNo, "2026-09-01 10:00:00", "1001", "2001", "3001", "BOX",
                        "2", "18.50", "武汉门店", "鲜榨果汁", "张三",
                        "2026-09-02", "¥37.00", "转账", "pay.png")));

        service.preflight(caller(), file,
                "https://mcn7tpu25x8w.feishu.cn/base/LUvobJ32Fa1dKesorBhc7VF8nye");
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.projectedRows()).isEqualTo(1);
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.projectionStatus()).isEqualTo("PROJECTED");
            assertThat(row.targetObjectType()).isEqualTo("SALES_ORDER");
            assertThat(row.message()).contains("飞书回款记录已导入Order");
        });
        assertThat(capturedOrder.get()).isNotNull();
        assertThat(capturedPayment.get()).satisfies(command -> {
            assertThat(command.sourceSystemCode()).isEqualTo("FEISHU");
            assertThat(command.sourceDocumentNo()).isEqualTo(sourceNo);
            assertThat(command.orderId()).isEqualTo(9001L);
            assertThat(command.paymentTime()).isEqualTo(Instant.parse("2026-09-01T16:00:00Z"));
            assertThat(command.paidAmount()).isEqualByComparingTo("37.00");
            assertThat(command.paymentMethodCode()).isEqualTo("BANK_TRANSFER");
            assertThat(command.voucherKeys()).isEmpty();
        });
    }

    @Test
    void runCreatesSalesPaymentFromSalesOrderPaidAmountWithSourceCreatedTimeFallback() {
        CapturingStore store = new CapturingStore();
        AtomicReference<SalesOrderCommand> capturedOrder = new AtomicReference<>();
        AtomicReference<SalesPaymentRecordCommand> capturedPayment = new AtomicReference<>();
        String sourceNo = "DD202608306725";
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, sourceNo),
                        capturedOrder, Optional.empty(), capturedPayment));
        MockMultipartFile file = xlsx("销售订单.xlsx", "销售订单表", List.of(
                List.of("订单编号", "创建时间", "客户ID", "商品ID", "规格ID", "单位编码",
                        "数量", "单价", "门店", "订单产品", "销售", "收款合计", "付款方式"),
                List.of(sourceNo, "2026-08-30 15:12:00", "1001", "2001", "3001", "BOX",
                        "1", "99.00", "思悦台球俱乐部", "油泼辣子拌面", "SYSTEM", "99.00", "转账")));

        service.preflight(caller(), file,
                "https://mcn7tpu25x8w.feishu.cn/base/LUvobJ32Fa1dKesorBhc7VF8nye");
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.projectedRows()).isEqualTo(1);
        assertThat(capturedOrder.get()).isNotNull();
        assertThat(capturedPayment.get()).satisfies(command -> {
            assertThat(command.sourceSystemCode()).isEqualTo("FEISHU");
            assertThat(command.sourceDocumentNo()).isEqualTo(sourceNo);
            assertThat(command.orderId()).isEqualTo(9001L);
            assertThat(command.paymentTime()).isEqualTo(Instant.parse("2026-08-30T07:12:00Z"));
            assertThat(command.paidAmount()).isEqualByComparingTo("99.00");
            assertThat(command.paymentMethodCode()).isEqualTo("BANK_TRANSFER");
        });
    }

    @Test
    void runCreatesSalesPaymentFromSalesOrderGenericAmountWhenPaymentSignalExists() {
        CapturingStore store = new CapturingStore();
        AtomicReference<SalesOrderCommand> capturedOrder = new AtomicReference<>();
        AtomicReference<SalesPaymentRecordCommand> capturedPayment = new AtomicReference<>();
        String sourceNo = "DD202608306725";
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, sourceNo),
                        capturedOrder, Optional.empty(), capturedPayment));
        MockMultipartFile file = xlsx("销售订单.xlsx", "销售订单表", List.of(
                List.of("订单编号", "创建时间", "客户ID", "商品ID", "规格ID", "单位编码",
                        "数量", "单价", "门店", "订单产品", "销售", "金额", "付款方式"),
                List.of(sourceNo, "2026-08-30 15:12:00", "1001", "2001", "3001", "BOX",
                        "1", "99.00", "思悦台球俱乐部", "油泼辣子拌面", "SYSTEM", "99.00", "转账")));

        service.preflight(caller(), file,
                "https://mcn7tpu25x8w.feishu.cn/base/LUvobJ32Fa1dKesorBhc7VF8nye");
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.projectedRows()).isEqualTo(1);
        assertThat(capturedOrder.get()).isNotNull();
        assertThat(capturedPayment.get()).satisfies(command -> {
            assertThat(command.sourceDocumentNo()).isEqualTo(sourceNo);
            assertThat(command.orderId()).isEqualTo(9001L);
            assertThat(command.paymentTime()).isEqualTo(Instant.parse("2026-08-30T07:12:00Z"));
            assertThat(command.paidAmount()).isEqualByComparingTo("99.00");
            assertThat(command.paymentMethodCode()).isEqualTo("BANK_TRANSFER");
        });
    }

    @Test
    void runDoesNotCreateSalesPaymentFromSalesOrderGenericAmountWithoutPaymentSignal() {
        CapturingStore store = new CapturingStore();
        AtomicReference<SalesOrderCommand> capturedOrder = new AtomicReference<>();
        AtomicReference<SalesPaymentRecordCommand> capturedPayment = new AtomicReference<>();
        String sourceNo = "DD202608306726";
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, sourceNo),
                        capturedOrder, Optional.empty(), capturedPayment));
        MockMultipartFile file = xlsx("销售订单.xlsx", "销售订单表", List.of(
                List.of("订单编号", "创建时间", "客户ID", "商品ID", "规格ID", "单位编码",
                        "数量", "单价", "门店", "订单产品", "销售", "金额"),
                List.of(sourceNo, "2026-08-30 15:12:00", "1001", "2001", "3001", "BOX",
                        "1", "99.00", "思悦台球俱乐部", "油泼辣子拌面", "SYSTEM", "99.00")));

        service.preflight(caller(), file,
                "https://mcn7tpu25x8w.feishu.cn/base/LUvobJ32Fa1dKesorBhc7VF8nye");
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.projectedRows()).isEqualTo(1);
        assertThat(capturedOrder.get()).isNotNull();
        assertThat(capturedPayment.get()).isNull();
    }

    @Test
    void runCreatesSalesPaymentWithCosVoucherKeyWhenFeishuAttachmentDownloads() {
        CapturingStore store = new CapturingStore();
        AtomicReference<SalesOrderCommand> capturedOrder = new AtomicReference<>();
        AtomicReference<SalesPaymentRecordCommand> capturedPayment = new AtomicReference<>();
        String sourceNo = "XS.20260901.0001";
        FakeFeishuBitableClient bitableClient = new FakeFeishuBitableClient();
        bitableClient.fields = List.of(new BitableField("fldVoucher", "付款凭证"));
        bitableClient.records = List.of(new BitableRecord("recOrder1", Map.<String, Object>of(
                "订单编号", sourceNo,
                "fldVoucher", List.of(Map.of(
                        "file_token", "file-token-pay-1",
                        "name", "pay.png",
                        "type", "image/png")))));
        bitableClient.downloads.put("file-token-pay-1",
                new DownloadedAttachment("file-token-pay-1", "pay.png", "image/png", new byte[]{9, 8, 7}));
        CapturingStorage storage = new CapturingStorage();
        FeishuAttachmentImportService attachmentService = new FeishuAttachmentImportService(
                store, bitableClient, storage,
                new FeishuAttachmentObjectKeyFactory("feishu-attachments"));
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, sourceNo),
                        capturedOrder, Optional.empty(), capturedPayment),
                null, null, null, null, attachmentService);
        MockMultipartFile file = xlsx("销售订单.xlsx", "销售订单表", List.of(
                List.of("订单编号", "创建时间", "客户ID", "商品ID", "规格ID", "单位编码",
                        "数量", "单价", "门店", "订单产品", "销售",
                        "回款日期", "收款合计", "付款方式", "回款凭证"),
                List.of(sourceNo, "2026-09-01 10:00:00", "1001", "2001", "3001", "BOX",
                        "2", "18.50", "武汉门店", "鲜榨果汁", "张三",
                        "2026-09-02", "¥37.00", "转账", "pay.png")));

        service.preflight(caller(), file,
                "https://mcn7tpu25x8w.feishu.cn/base/appToken123?table=tblOrder&view=vewOrder");
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.uploadedAttachmentCount()).isEqualTo(1);
        assertThat(storage.objects).hasSize(1);
        String objectKey = storage.objects.keySet().iterator().next();
        assertThat(capturedOrder.get()).satisfies(command ->
                assertThat(command.paymentVoucherKeys()).containsExactly(objectKey));
        assertThat(capturedPayment.get()).satisfies(command ->
                assertThat(command.voucherKeys()).containsExactly(objectKey));
        assertThat(objectKey)
                .startsWith(TENANT_ID + "/feishu-attachments/FEISHU_SALES_ORDER/XS_20260901_0001/")
                .contains("/回款凭证/")
                .endsWith(".png");
    }

    @Test
    void runKeepsSuccessfulAttachmentRowsWhenAnotherRowUploadFails() {
        CapturingStore store = new CapturingStore();
        List<SalesOrderCommand> capturedOrders = new ArrayList<>();
        List<SalesPaymentRecordCommand> capturedPayments = new ArrayList<>();
        String goodSourceNo = "DD202608232985";
        String failedSourceNo = "DD202608232986";
        FakeFeishuBitableClient bitableClient = new FakeFeishuBitableClient();
        bitableClient.fields = List.of(new BitableField("fldVoucher", "付款凭证"));
        bitableClient.records = List.of(
                new BitableRecord("recOrder1", Map.<String, Object>of(
                        "订单编号", goodSourceNo,
                        "fldVoucher", List.of(Map.of(
                                "file_token", "file-token-pay-7910",
                                "name", "IMG_7910.JPEG",
                                "type", "image/jpeg")))),
                new BitableRecord("recOrder2", Map.<String, Object>of(
                        "订单编号", failedSourceNo,
                        "fldVoucher", List.of(Map.of(
                                "file_token", "file-token-pay-failed",
                                "name", "IMG_FAILED.JPEG",
                                "type", "image/jpeg")))));
        bitableClient.downloads.put("file-token-pay-7910",
                new DownloadedAttachment("file-token-pay-7910", "IMG_7910.JPEG", "image/jpeg",
                        new byte[]{7, 9, 1, 0}));
        bitableClient.downloads.put("file-token-pay-failed",
                new DownloadedAttachment("file-token-pay-failed", "IMG_FAILED.JPEG", "image/jpeg",
                        new byte[]{1, 2, 3, 4}));
        CapturingStorage storage = new CapturingStorage();
        storage.failObjectKeyFragments.add(failedSourceNo);
        FeishuAttachmentImportService attachmentService = new FeishuAttachmentImportService(
                store, bitableClient, storage,
                new FeishuAttachmentObjectKeyFactory("feishu-attachments"));
        FeishuImportBundleService service = service(store,
                orderProjectionClientCapturingLists(Optional.empty(), salesOrderDetail(9001L, goodSourceNo),
                        capturedOrders, Optional.empty(), capturedPayments),
                null, null, null, null, attachmentService);
        MockMultipartFile file = xlsx("全国销售订单列表.xlsx", "销售订单表", List.of(
                List.of("订单编号", "创建时间", "客户ID", "商品ID", "规格ID", "单位编码",
                        "数量", "单价", "门店", "订单产品", "销售",
                        "回款日期", "收款合计", "付款方式", "回款凭证"),
                List.of(goodSourceNo, "2026-08-23 10:00:00", "1001", "2001", "3001", "BOX",
                        "2", "18.50", "武汉门店", "鲜榨果汁", "张三",
                        "2026-08-23", "¥37.00", "转账", "IMG_7910.JPEG"),
                List.of(failedSourceNo, "2026-08-23 10:05:00", "1001", "2001", "3001", "BOX",
                        "1", "18.50", "武汉门店", "鲜榨果汁", "张三",
                        "2026-08-23", "¥18.50", "转账", "IMG_FAILED.JPEG")));

        service.preflight(caller(), file,
                "https://mcn7tpu25x8w.feishu.cn/base/appToken123?table=tblOrder&view=vewOrder");
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.uploadedAttachmentCount()).isEqualTo(1);
        assertThat(result.failedAttachmentRows()).isEqualTo(1);
        assertThat(storage.objects).hasSize(1);
        String objectKey = storage.objects.keySet().iterator().next();
        assertThat(objectKey)
                .startsWith(TENANT_ID + "/feishu-attachments/FEISHU_SALES_ORDER/DD202608232985/")
                .contains("/回款凭证/")
                .endsWith(".jpeg");
        assertThat(capturedOrders).anySatisfy(command ->
                assertThat(command.paymentVoucherKeys()).containsExactly(objectKey));
        assertThat(capturedPayments).anySatisfy(command ->
                assertThat(command.voucherKeys()).containsExactly(objectKey));
        assertThat(store.attachmentUpdates).singleElement().satisfies(update -> {
            assertThat(update.values().get("回款凭证")).isEqualTo(objectKey);
            assertThat(update.attachmentRefs().get("回款凭证")).containsExactly(objectKey);
        });
        assertThat(result.rows()).anySatisfy(row -> {
            assertThat(row.sourceDocumentNo()).isEqualTo(goodSourceNo);
            assertThat(row.projectionStatus()).isEqualTo("PROJECTED");
        });
        assertThat(result.rows()).anySatisfy(row -> {
            assertThat(row.sourceDocumentNo()).isEqualTo(failedSourceNo);
            assertThat(row.projectionStatus()).isEqualTo("WAITING_MAPPING");
            assertThat(row.message()).contains("飞书附件下载或COS上传失败");
        });
    }

    @Test
    void runResolvesAttachmentWithTableAliasFieldAliasAndFileNameStem() {
        CapturingStore store = new CapturingStore();
        AtomicReference<SalesOrderCommand> capturedOrder = new AtomicReference<>();
        AtomicReference<SalesPaymentRecordCommand> capturedPayment = new AtomicReference<>();
        String sourceNo = "DD202608303357";
        String sourceAttachmentName = "WecomSave_7e7b65684636f49b588c3c2f1c3d9867";
        FakeFeishuBitableClient bitableClient = new FakeFeishuBitableClient();
        bitableClient.tables = List.of(new BitableTable("tblOrder", "销售订单表"));
        bitableClient.fields = List.of(new BitableField("fldVoucher", "付款凭证"));
        bitableClient.records = List.of(new BitableRecord("recOrder1", Map.<String, Object>of(
                "订单编号", sourceNo,
                "fldVoucher", List.of(Map.of(
                        "file_token", "file-token-pay-wecom",
                        "name", sourceAttachmentName + ".jpeg",
                        "type", "image/jpeg")))));
        bitableClient.downloads.put("file-token-pay-wecom",
                new DownloadedAttachment("file-token-pay-wecom", sourceAttachmentName + ".jpeg",
                        "image/jpeg", new byte[]{7, 6, 5, 4}));
        CapturingStorage storage = new CapturingStorage();
        FeishuAttachmentImportService attachmentService = new FeishuAttachmentImportService(
                store, bitableClient, storage,
                new FeishuAttachmentObjectKeyFactory("feishu-attachments"));
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, sourceNo),
                        capturedOrder, Optional.empty(), capturedPayment),
                null, null, null, null, attachmentService);
        MockMultipartFile file = xlsx("全国销售订单列表.xlsx", "全国销售订单列表", List.of(
                List.of("订单编号", "创建时间", "客户ID", "商品ID", "规格ID", "单位编码",
                        "数量", "单价", "门店", "订单产品", "销售",
                        "回款日期", "收款合计", "付款方式", "回款凭证"),
                List.of(sourceNo, "2026-08-26 10:00:00", "1001", "2001", "3001", "BOX",
                        "2", "205.20", "789台球俱乐部（武汉中南店）", "葱油鸡肉菌菇拌面",
                        "胡毅然", "2026-08-29", "¥410.40", "其他", sourceAttachmentName)));

        service.preflight(caller(), file,
                "https://mcn7tpu25x8w.feishu.cn/base/appToken123");
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.uploadedAttachmentCount()).isEqualTo(1);
        assertThat(store.saved.tables()).singleElement().satisfies(table ->
                assertThat(table.tableCode()).isEqualTo("FEISHU_SALES_ORDER"));
        assertThat(storage.objects).hasSize(1);
        String objectKey = storage.objects.keySet().iterator().next();
        assertThat(objectKey)
                .startsWith(TENANT_ID + "/feishu-attachments/FEISHU_SALES_ORDER/DD202608303357/")
                .contains("/回款凭证/")
                .endsWith(".jpeg");
        assertThat(capturedOrder.get()).satisfies(command ->
                assertThat(command.paymentVoucherKeys()).containsExactly(objectKey));
        assertThat(capturedPayment.get()).satisfies(command ->
                assertThat(command.voucherKeys()).containsExactly(objectKey));
    }

    @Test
    void runFindsAttachmentTokenByFileNameWhenRecordFieldNameDiffers() {
        CapturingStore store = new CapturingStore();
        AtomicReference<SalesOrderCommand> capturedOrder = new AtomicReference<>();
        AtomicReference<SalesPaymentRecordCommand> capturedPayment = new AtomicReference<>();
        String sourceNo = "DD202608232985";
        FakeFeishuBitableClient bitableClient = new FakeFeishuBitableClient();
        bitableClient.tables = List.of(new BitableTable("tblOrder", "销售订单表"));
        bitableClient.fields = List.of(new BitableField("fldPayImage", "附件图片"));
        bitableClient.records = List.of(new BitableRecord("recOrder1", Map.<String, Object>of(
                "订单编号", sourceNo,
                "fldPayImage", List.of(Map.of(
                        "file_token", "file-token-img-7910",
                        "name", "IMG_7910.JPEG",
                        "type", "image/jpeg")))));
        bitableClient.downloads.put("file-token-img-7910",
                new DownloadedAttachment("file-token-img-7910", "IMG_7910.JPEG",
                        "image/jpeg", new byte[]{9, 1, 0}));
        CapturingStorage storage = new CapturingStorage();
        FeishuAttachmentImportService attachmentService = new FeishuAttachmentImportService(
                store, bitableClient, storage,
                new FeishuAttachmentObjectKeyFactory("feishu-attachments"));
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, sourceNo),
                        capturedOrder, Optional.empty(), capturedPayment),
                null, null, null, null, attachmentService);
        MockMultipartFile file = xlsx("全国销售订单列表.xlsx", "销售订单表", List.of(
                List.of("订单编号", "创建时间", "客户ID", "商品ID", "规格ID", "单位编码",
                        "数量", "单价", "门店", "订单产品", "销售",
                        "回款日期", "收款合计", "付款方式", "回款凭证"),
                List.of(sourceNo, "2026-08-23 10:00:00", "1001", "2001", "3001", "BOX",
                        "2", "205.20", "武汉门店", "葱油鸡肉菌菇拌面",
                        "林逸民", "2026-08-29", "¥410.40", "其他", "IMG_7910.JPEG")));

        service.preflight(caller(), file,
                "https://mcn7tpu25x8w.feishu.cn/base/appToken123?table=tblOrder&view=vewOrder");
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.uploadedAttachmentCount()).isEqualTo(1);
        assertThat(storage.objects).hasSize(1);
        String objectKey = storage.objects.keySet().iterator().next();
        assertThat(objectKey)
                .startsWith(TENANT_ID + "/feishu-attachments/FEISHU_SALES_ORDER/DD202608232985/")
                .contains("/回款凭证/")
                .endsWith(".jpeg");
        assertThat(capturedOrder.get()).satisfies(command ->
                assertThat(command.paymentVoucherKeys()).containsExactly(objectKey));
        assertThat(capturedPayment.get()).satisfies(command ->
                assertThat(command.voucherKeys()).containsExactly(objectKey));
    }

    @Test
    void runDownloadsVoucherFromWrappedFeishuAttachmentField() {
        CapturingStore store = new CapturingStore();
        AtomicReference<SalesOrderCommand> capturedOrder = new AtomicReference<>();
        AtomicReference<SalesPaymentRecordCommand> capturedPayment = new AtomicReference<>();
        String sourceNo = "DD202608232985";
        FakeFeishuBitableClient bitableClient = new FakeFeishuBitableClient();
        bitableClient.tables = List.of(new BitableTable("tblOrder", "销售订单表"));
        bitableClient.fields = List.of(new BitableField("fldVoucher", "回款凭证"));
        bitableClient.records = List.of(new BitableRecord("recOrder1", Map.<String, Object>of(
                "订单编号", sourceNo,
                "fldVoucher", Map.of(
                        "type", 17,
                        "value", List.of(Map.of(
                                "file_token", "file-token-img-7910",
                                "name", "IMG_7910.JPEG",
                                "type", "image/jpeg"))))));
        bitableClient.downloads.put("file-token-img-7910",
                new DownloadedAttachment("file-token-img-7910", "IMG_7910.JPEG",
                        "image/jpeg", new byte[]{9, 1, 0}));
        CapturingStorage storage = new CapturingStorage();
        FeishuAttachmentImportService attachmentService = new FeishuAttachmentImportService(
                store, bitableClient, storage,
                new FeishuAttachmentObjectKeyFactory("feishu-attachments"));
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, sourceNo),
                        capturedOrder, Optional.empty(), capturedPayment),
                null, null, null, null, attachmentService);
        MockMultipartFile file = xlsx("全国销售订单列表.xlsx", "销售订单表", List.of(
                List.of("订单编号", "创建时间", "客户ID", "商品ID", "规格ID", "单位编码",
                        "数量", "单价", "门店", "订单产品", "销售",
                        "回款日期", "收款合计", "付款方式", "回款凭证"),
                List.of(sourceNo, "2026-08-23 10:00:00", "1001", "2001", "3001", "BOX",
                        "2", "205.20", "武汉门店", "葱油鸡肉菌菇拌面",
                        "林逸民", "2026-08-29", "¥410.40", "其他", "IMG_7910.JPEG")));

        service.preflight(caller(), file,
                "https://mcn7tpu25x8w.feishu.cn/base/appToken123?table=tblOrder&view=vewOrder");
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.uploadedAttachmentCount()).isEqualTo(1);
        assertThat(storage.objects).hasSize(1);
        String objectKey = storage.objects.keySet().iterator().next();
        assertThat(objectKey)
                .startsWith(TENANT_ID + "/feishu-attachments/FEISHU_SALES_ORDER/DD202608232985/")
                .contains("/回款凭证/")
                .endsWith(".jpeg");
        assertThat(capturedOrder.get()).satisfies(command ->
                assertThat(command.paymentVoucherKeys()).containsExactly(objectKey));
        assertThat(capturedPayment.get()).satisfies(command ->
                assertThat(command.voucherKeys()).containsExactly(objectKey));
        assertThat(store.attachmentUpdates).singleElement().satisfies(update ->
                assertThat(update.attachmentRefs().get("回款凭证")).containsExactly(objectKey));
    }

    @Test
    void salesPaymentFromSalesOrderInheritsOrderOwnerEmployeeCode() {
        String sourceNo = "DD202608232985";
        StoredRawRow row = storedRow(FeishuSalesOrderImportMapper.TABLE_CODE, "ORDER", "SALES_ORDER",
                Map.of("订单编号", sourceNo,
                        "销售", "林逸民",
                        "回款日期", "2026-08-29",
                        "收款合计", "410.40",
                        "付款方式", "其他"));
        SalesOrderDetailView order = salesOrderDetail(9001L, sourceNo, "EMP202607200627", "林逸民");

        FeishuSalesPaymentImportMapper.PaymentProjectionPlan plan =
                new FeishuSalesPaymentImportMapper().planFromSalesOrder(row, order);

        assertThat(plan.status()).isEqualTo("PENDING");
        assertThat(plan.command()).satisfies(command -> {
            assertThat(command.collectorStaffCode()).isEqualTo("EMP202607200627");
            assertThat(command.collectorNameSnapshot()).isEqualTo("林逸民");
        });
    }

    @Test
    void runResolvesSalesPaymentCollectorEmployeeCodeFromHr() {
        CapturingStore store = new CapturingStore();
        AtomicReference<SalesOrderCommand> capturedOrder = new AtomicReference<>();
        AtomicReference<SalesPaymentRecordCommand> capturedPayment = new AtomicReference<>();
        AtomicReference<ExternalEmployeeResolveCommand> capturedEmployeeResolve = new AtomicReference<>();
        AtomicReference<CallerIdentity> capturedEmployeeCaller = new AtomicReference<>();
        String sourceNo = "DD202608232985";
        HrEmployeeProjectionClient hrClient = new HrEmployeeProjectionClient() {
            @Override
            public ExternalEmployeeSyncResult sync(CallerIdentity caller, String sourceSystem,
                                                   List<ExternalEmployeeRowCommand> rows) {
                return new ExternalEmployeeSyncResult(0, 0, 0, 0, 0, List.of(), List.of());
            }

            @Override
            public List<ExternalEmployeeResolvedView> resolve(
                    CallerIdentity caller, ExternalEmployeeResolveCommand command) {
                capturedEmployeeCaller.set(caller);
                capturedEmployeeResolve.set(command);
                return List.of(new ExternalEmployeeResolvedView("FEISHU", "FEISHU_SALES_STAFF",
                        null, 501L, "EMP202607200627", "林逸民", "INACTIVE",
                        "销售", null, "销售员", null, null, null, null, "武汉", NOW));
            }
        };
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.of(salesOrderDetail(9001L, sourceNo)),
                        salesOrderDetail(9001L, sourceNo), capturedOrder,
                        Optional.empty(), capturedPayment),
                hrClient, null, null, null);
        MockMultipartFile file = xlsx("回款记录.xlsx", "回款记录表", List.of(
                List.of("回款编号", "关联订单", "回款日期", "实际回款额", "回款人", "付款方式"),
                List.of("PAY202608290001", sourceNo, "2026/08/29", "410.40", "林逸民", "其他")));

        service.preflight(caller(), file, null);
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(capturedEmployeeCaller.get().permissions()).contains("hr:employee:read");
        assertThat(capturedEmployeeResolve.get()).satisfies(command ->
                assertThat(command.employeeNames()).containsExactly("林逸民"));
        assertThat(capturedPayment.get()).satisfies(command -> {
            assertThat(command.collectorStaffCode()).isEqualTo("EMP202607200627");
            assertThat(command.collectorNameSnapshot()).isEqualTo("林逸民");
        });
    }

    @Test
    void runKeepsNonSalesHrEmployeeOutOfSalesPaymentAttribution() {
        CapturingStore store = new CapturingStore();
        AtomicReference<SalesOrderCommand> capturedOrder = new AtomicReference<>();
        AtomicReference<SalesPaymentRecordCommand> capturedPayment = new AtomicReference<>();
        AtomicReference<ExternalEmployeeResolveCommand> capturedEmployeeResolve = new AtomicReference<>();
        String sourceNo = "DD202608232985";
        HrEmployeeProjectionClient hrClient = new HrEmployeeProjectionClient() {
            @Override
            public ExternalEmployeeSyncResult sync(CallerIdentity caller, String sourceSystem,
                                                   List<ExternalEmployeeRowCommand> rows) {
                return new ExternalEmployeeSyncResult(0, 0, 0, 0, 0, List.of(), List.of());
            }

            @Override
            public List<ExternalEmployeeResolvedView> resolve(
                    CallerIdentity caller, ExternalEmployeeResolveCommand command) {
                capturedEmployeeResolve.set(command);
                return List.of(new ExternalEmployeeResolvedView("FEISHU", "FEISHU_SALES_STAFF",
                        null, 501L, "EMP202609090001", "高月然", "ACTIVE",
                        "财务", null, "出纳", "财务部", null, null, null, "武汉", NOW));
            }
        };
        SalesOrderDetailView existingOrder = salesOrderDetail(9001L, sourceNo,
                "EMP202607200627", "林逸民");
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.of(existingOrder), existingOrder, capturedOrder,
                        Optional.empty(), capturedPayment),
                hrClient, null, null, null);
        MockMultipartFile file = xlsx("回款记录.xlsx", "回款记录表", List.of(
                List.of("回款编号", "关联订单", "回款日期", "实际回款额", "回款人", "付款方式"),
                List.of("PAY202608290001", sourceNo, "2026/08/29", "410.40", "高月然", "其他")));

        service.preflight(caller(), file, null);
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(capturedEmployeeResolve.get()).satisfies(command ->
                assertThat(command.employeeNames()).containsExactly("高月然"));
        assertThat(capturedPayment.get()).satisfies(command -> {
            assertThat(command.collectorStaffCode()).isNull();
            assertThat(command.collectorNameSnapshot()).isEqualTo("高月然");
        });
    }

    @Test
    void runProjectsBusinessRowWithPendingVoucherRemarkWhenAttachmentFails() {
        CapturingStore store = new CapturingStore();
        AtomicReference<SalesOrderCommand> capturedOrder = new AtomicReference<>();
        AtomicReference<SalesPaymentRecordCommand> capturedPayment = new AtomicReference<>();
        String sourceNo = "DD202608303358";
        FakeFeishuBitableClient bitableClient = new FakeFeishuBitableClient();
        bitableClient.tables = List.of(new BitableTable("tblOrder", "销售订单表"));
        bitableClient.fields = List.of(new BitableField("fldVoucher", "回款凭证"));
        bitableClient.records = List.of(new BitableRecord("recOrder1", Map.<String, Object>of(
                "订单编号", sourceNo,
                "fldVoucher", List.of(Map.of(
                        "file_token", "file-token-missing",
                        "name", "missing.jpeg",
                        "type", "image/jpeg")))));
        FeishuAttachmentImportService attachmentService = new FeishuAttachmentImportService(
                store, bitableClient, new CapturingStorage(),
                new FeishuAttachmentObjectKeyFactory("feishu-attachments"));
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, sourceNo),
                        capturedOrder, Optional.empty(), capturedPayment),
                null, null, null, null, attachmentService);
        MockMultipartFile file = xlsx("销售订单.xlsx", "销售订单表", List.of(
                List.of("订单编号", "创建时间", "客户ID", "商品ID", "规格ID", "单位编码",
                        "数量", "单价", "门店", "订单产品", "销售",
                        "回款日期", "收款合计", "付款方式", "回款凭证"),
                List.of(sourceNo, "2026-08-26 10:00:00", "1001", "2001", "3001", "BOX",
                        "2", "205.20", "武汉门店", "葱油鸡肉菌菇拌面",
                        "胡毅然", "2026-08-29", "¥410.40", "其他", "missing.jpeg")));

        service.preflight(caller(), file,
                "https://mcn7tpu25x8w.feishu.cn/base/appToken123");
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.uploadedAttachmentCount()).isZero();
        assertThat(result.failedAttachmentRows()).isEqualTo(1);
        assertThat(capturedOrder.get()).satisfies(command -> {
            assertThat(command.paymentVoucherKeys()).isEmpty();
            assertThat(command.remark()).contains("回款凭证待补录：missing.jpeg");
        });
        assertThat(capturedPayment.get()).satisfies(command -> {
            assertThat(command.voucherKeys()).isEmpty();
            assertThat(command.remark()).contains("回款凭证待补录：missing.jpeg");
        });
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.projectionStatus()).isEqualTo("WAITING_MAPPING");
            assertThat(row.message()).contains("飞书附件下载或COS上传失败");
        });
        assertThat(store.projectionUpdates).singleElement().satisfies(update ->
                assertThat(update.projectionStatus()).isEqualTo("WAITING_MAPPING"));
    }

    @Test
    void formalRunReplaysProjectedSalesOrderAndUpdatesPaymentVoucherKey() {
        CapturingStore store = new CapturingStore();
        AtomicReference<SalesOrderCommand> capturedOrder = new AtomicReference<>();
        AtomicReference<SalesPaymentRecordCommand> capturedPayment = new AtomicReference<>();
        String sourceNo = "DD202608303361";
        FakeFeishuBitableClient bitableClient = new FakeFeishuBitableClient();
        bitableClient.fields = List.of(new BitableField("fldVoucher", "回款凭证"));
        bitableClient.records = List.of(new BitableRecord("recOrder1", Map.<String, Object>of(
                "订单编号", sourceNo,
                "fldVoucher", List.of(Map.of(
                        "file_token", "file-token-pay-4687",
                        "name", "IMG_4687.JPEG",
                        "type", "image/jpeg")))));
        bitableClient.downloads.put("file-token-pay-4687",
                new DownloadedAttachment("file-token-pay-4687", "IMG_4687.JPEG", "image/jpeg",
                        new byte[]{4, 6, 8, 7}));
        CapturingStorage storage = new CapturingStorage();
        FeishuAttachmentImportService attachmentService = new FeishuAttachmentImportService(
                store, bitableClient, storage,
                new FeishuAttachmentObjectKeyFactory("feishu-attachments"));
        SalesPaymentRecordCommand existingPaymentCommand = new SalesPaymentRecordCommand(
                null, "FEISHU", sourceNo, 9001L, null, "苏子豪",
                Instant.parse("2026-08-30T16:00:00Z"), "OTHER",
                new BigDecimal("296.40"), List.of(), null, 1);
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.of(salesOrderDetail(9001L, sourceNo)),
                        salesOrderDetail(9001L, sourceNo), capturedOrder,
                        Optional.of(salesPaymentDetail(9101L, "PAY202608314992",
                                existingPaymentCommand, 1)),
                        capturedPayment),
                null, null, null, null, attachmentService);
        MockMultipartFile file = xlsx("全国销售订单列表.xlsx", "销售订单表", List.of(
                List.of("订单编号门店", "订单编号", "创建时间", "门店", "销售",
                        "回款日期", "收款合计", "付款方式", "回款凭证"),
                List.of(sourceNo + "-思悦台球俱乐部", sourceNo, "2026-08-30 15:53:28",
                        "思悦台球俱乐部", "苏子豪", "2026/08/31", "¥296.40", "其他",
                        "IMG_4687.JPEG")));

        service.preflight(caller(), file,
                "https://mcn7tpu25x8w.feishu.cn/base/appToken123?table=tblOrder&view=vewOrder");
        PreflightRawRow row = store.saved.rawRows().getFirst();
        store.saved = new PreflightBatch(store.saved.id(), store.saved.tenantId(), store.saved.createdBy(),
                store.saved.sourceSystem(), store.saved.sourceUrl(), store.saved.originalFileName(),
                store.saved.fileSizeBytes(), store.saved.fileSha256(), store.saved.status(),
                store.saved.totalSheets(), store.saved.totalRows(), store.saved.duplicateRows(),
                store.saved.attachmentReferenceCount(), store.saved.createdAt(), store.saved.tables(),
                store.saved.issues(), List.of(new PreflightRawRow(row.id(), row.tableId(), row.sheetName(),
                row.tableCode(), row.domainCode(), row.objectType(), row.rowNumber(), row.sourceDocumentNo(),
                row.sourceCreatedAt(), row.rowHash(), row.values(), row.attachmentRefs(),
                row.deduplicationKey(), row.duplicateScope(), row.duplicateOfRawRowId(),
                row.importStatus(), "PROJECTED")));

        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.uploadedAttachmentCount()).isEqualTo(1);
        assertThat(storage.objects).hasSize(1);
        String objectKey = storage.objects.keySet().iterator().next();
        assertThat(capturedPayment.get()).satisfies(command -> {
            assertThat(command.sourceDocumentNo()).isEqualTo(sourceNo);
            assertThat(command.orderId()).isEqualTo(9001L);
            assertThat(command.voucherKeys()).containsExactly(objectKey);
            assertThat(command.revision()).isEqualTo(1);
        });
        assertThat(result.rows()).singleElement().satisfies(runRow ->
                assertThat(runRow.message()).contains("已按本次导入内容更新"));
    }

    @Test
    void runDoesNotCreateSalesPaymentForUnpaidSalesOrderWithPaymentDateOnly() {
        CapturingStore store = new CapturingStore();
        AtomicReference<SalesOrderCommand> capturedOrder = new AtomicReference<>();
        AtomicReference<SalesPaymentRecordCommand> capturedPayment = new AtomicReference<>();
        String sourceNo = "XS.20260901.0002";
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, sourceNo),
                        capturedOrder, Optional.empty(), capturedPayment));
        MockMultipartFile file = xlsx("销售订单.xlsx", "销售订单表", List.of(
                List.of("订单编号", "创建时间", "客户ID", "商品ID", "规格ID", "单位编码",
                        "数量", "单价", "门店", "订单产品", "销售", "回款日期", "收款合计"),
                List.of(sourceNo, "2026-09-01 10:00:00", "1001", "2001", "3001", "BOX",
                        "2", "18.50", "武汉门店", "鲜榨果汁", "张三", "2026-09-02", "¥0.00")));

        service.preflight(caller(), file,
                "https://mcn7tpu25x8w.feishu.cn/base/LUvobJ32Fa1dKesorBhc7VF8nye");
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.projectedRows()).isEqualTo(1);
        assertThat(result.waitingMappingRows()).isZero();
        assertThat(result.failedRows()).isZero();
        assertThat(capturedOrder.get()).isNotNull();
        assertThat(capturedPayment.get()).isNull();
    }

    @Test
    void runAppendsSalesOrderLinesToExistingOrderWhenHeaderWasImportedEarlier() {
        CapturingStore store = new CapturingStore();
        AtomicReference<SalesOrderCommand> capturedOrder = new AtomicReference<>();
        String sourceNo = "DD202609013380";
        ErpProductProjectionClient erpClient = new ErpProductProjectionClient() {
            @Override
            public ExternalProductSyncResult sync(CallerIdentity caller, String sourceSystem,
                                                  List<ExternalProductRowCommand> rows) {
                return new ExternalProductSyncResult(0, 0, 0, 0, 0, List.of(), List.of());
            }

            @Override
            public List<ExternalProductResolvedView> resolve(CallerIdentity caller, String preferredSourceSystem,
                                                             List<ExternalProductResolveRowCommand> rows) {
                return rows.stream()
                        .map(row -> new ExternalProductResolvedView(row.referenceId(),
                                2001L, 3001L, "PRD202609010001", "SKU202609010001",
                                "金汤肥牛", "默认规格", "BOX", "DINGHUOBAO",
                                "PRODUCT_NAME_FUZZY_UNIQUE", 70, "MATCHED", "已匹配"))
                        .toList();
            }
        };
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.of(salesOrderDetail(9001L, sourceNo)),
                        salesOrderDetail(9001L, sourceNo), capturedOrder),
                null, null, erpClient, null);
        MockMultipartFile lineFile = xlsx("订单明细列表.xlsx", "订单明细表", List.of(
                List.of("订单明细号", "关联订单", "产品编号", "数量(箱)", "设计单价", "实际小计", "优惠"),
                List.of("ODD202609013380001", sourceNo + "-武汉门店",
                        "杨掌柜-金汤肥牛-12桶/箱", "1", "78", "¥74.10", "95%")));

        service.preflight(caller(), lineFile, null);
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.projectedRows()).isEqualTo(1);
        assertThat(result.waitingMappingRows()).isZero();
        assertThat(result.failedRows()).isZero();
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.projectionStatus()).isEqualTo("PROJECTED");
            assertThat(row.targetObjectType()).isEqualTo("SALES_ORDER_LINE");
            assertThat(row.message()).contains("已补充到已存在销售订单");
        });
        assertThat(capturedOrder.get()).satisfies(command ->
                assertThat(command.lines()).singleElement().satisfies(line -> {
                    assertThat(line.productNameSnapshot()).isEqualTo("金汤肥牛");
                    assertThat(line.quantity()).isEqualByComparingTo("1");
                    assertThat(line.unitPrice()).isEqualByComparingTo("78");
                    assertThat(line.discountAmount()).isEqualByComparingTo("3.90");
                }));
    }

    @Test
    void runRefreshesExistingSalesOrderHeaderWhenLinesUnchangedButAmountsAreStale() {
        CapturingStore store = new CapturingStore();
        AtomicReference<SalesOrderCommand> capturedOrder = new AtomicReference<>();
        String sourceNo = "DD202608132547";
        ErpProductProjectionClient erpClient = new ErpProductProjectionClient() {
            @Override
            public ExternalProductSyncResult sync(CallerIdentity caller, String sourceSystem,
                                                  List<ExternalProductRowCommand> rows) {
                return new ExternalProductSyncResult(0, 0, 0, 0, 0, List.of(), List.of());
            }

            @Override
            public List<ExternalProductResolvedView> resolve(CallerIdentity caller, String preferredSourceSystem,
                                                             List<ExternalProductResolveRowCommand> rows) {
                return rows.stream()
                        .map(row -> new ExternalProductResolvedView(row.referenceId(),
                                2001L, 3001L, "PRD202609010001", "SKU202609010001",
                                "金汤肥牛", "默认规格", "BOX", "DINGHUOBAO",
                                "PRODUCT_NAME_FUZZY_UNIQUE", 70, "MATCHED", "已匹配"))
                        .toList();
            }
        };
        SalesOrderDetailView staleOrder = salesOrderDetail(9001L, sourceNo,
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(salesOrderLine(1L, 2001L, 3001L, "金汤肥牛", BigDecimal.ONE,
                        new BigDecimal("78"), new BigDecimal("3.90"))));
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.of(staleOrder), salesOrderDetail(9001L, sourceNo), capturedOrder),
                null, null, erpClient, null);
        MockMultipartFile lineFile = xlsx("订单明细列表.xlsx", "订单明细表", List.of(
                List.of("订单明细号", "关联订单", "产品编号", "数量(箱)", "设计单价", "实际小计", "优惠"),
                List.of("ODD202608132547001", sourceNo + "-武汉门店",
                        "杨掌柜-金汤肥牛-12桶/箱", "1", "78", "¥74.10", "95%")));

        service.preflight(caller(), lineFile, null);
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.projectedRows()).isEqualTo(1);
        assertThat(capturedOrder.get()).isNotNull();
        assertThat(capturedOrder.get().lines()).singleElement().satisfies(line -> {
            assertThat(line.quantity()).isEqualByComparingTo("1");
            assertThat(line.unitPrice()).isEqualByComparingTo("78");
            assertThat(line.discountAmount()).isEqualByComparingTo("3.90");
        });
    }

    @Test
    void runSkipsSalesOrderAndLinesWhenOrderQuantityIsZero() {
        CapturingStore store = new CapturingStore();
        AtomicReference<SalesOrderCommand> capturedOrder = new AtomicReference<>();
        String sourceNo = "DD202608243008";
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, sourceNo), capturedOrder));
        MockMultipartFile file = xlsx("飞书全量.xlsx", List.of(
                new SheetSource("销售订单表", List.of(
                        List.of("订单编号", "创建时间", "门店", "销售",
                                "销售日期", "数量", "实际小计", "订单产品", "付款状态"),
                        List.of(sourceNo, "2026-08-24 10:00:00", "M4台球俱乐部",
                                "朱明", "2026/08/24", "0", "¥0.00",
                                "油泼辣子拌面", "未付款"))),
                new SheetSource("订单明细表", List.of(
                        List.of("订单明细号", "关联订单", "订单产品", "数量(箱)", "设计单价", "实际小计"),
                        List.of("ODD202608243008001", sourceNo + "-M4台球俱乐部",
                                "油泼辣子拌面", "4", "78", "¥312.00")))));

        service.preflight(caller(), file, null);
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.projectedRows()).isZero();
        assertThat(result.skippedRows()).isEqualTo(2);
        assertThat(capturedOrder.get()).isNull();
        assertThat(result.rows()).allSatisfy(row -> {
            assertThat(row.projectionStatus()).isEqualTo("SKIPPED");
            assertThat(row.message()).contains("数量为0");
        });
    }

    @Test
    void runDerivesDiscountFromSalesOrderSubtotalAndActualSubtotal() {
        CapturingStore store = new CapturingStore();
        AtomicReference<SalesOrderCommand> capturedOrder = new AtomicReference<>();
        String sourceNo = "DD202608303365";
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, sourceNo), capturedOrder));
        MockMultipartFile file = xlsx("全国销售订单列表.xlsx", "销售订单表", List.of(
                List.of("订单编号", "创建时间", "门店", "销售", "销售日期", "数量", "小计",
                        "实际小计", "订单产品", "付款状态"),
                List.of(sourceNo, "2026-08-30 22:29:03", "准度桌球", "范瀚阳",
                        "2026/08/30", "10", "¥720.00", "¥576.00",
                        "酸麻粉面菜蛋", "已结清")));

        service.preflight(caller(), file, null);
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.projectedRows()).isEqualTo(1);
        assertThat(capturedOrder.get()).satisfies(command -> {
            assertThat(command.discountAmount()).isNull();
            assertThat(command.lines()).singleElement().satisfies(line -> {
                assertThat(line.quantity()).isEqualByComparingTo("10");
                assertThat(line.unitPrice()).isEqualByComparingTo("72.000000");
                assertThat(line.discountAmount()).isEqualByComparingTo("144.00");
            });
        });
    }

    @Test
    void runClearsExistingSalesOrderAmountWhenOrderSheetAmountIsExplicitZero() {
        CapturingStore store = new CapturingStore();
        AtomicReference<SalesOrderCommand> capturedOrder = new AtomicReference<>();
        String sourceNo = "DD202608062285";
        SalesOrderDetailView staleOrder = salesOrderDetail(9001L, sourceNo,
                new BigDecimal("4"), new BigDecimal("312"), BigDecimal.ZERO, new BigDecimal("312"),
                List.of(salesOrderLine(1L, 2001L, 3001L, "金汤肥牛", new BigDecimal("4"),
                        new BigDecimal("78"), BigDecimal.ZERO)));
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.of(staleOrder), salesOrderDetail(9001L, sourceNo), capturedOrder));
        MockMultipartFile file = xlsx("全国销售订单列表.xlsx", "销售订单表", List.of(
                List.of("订单编号门店", "订单编号", "创建时间", "门店", "销售",
                        "销售日期", "数量", "实际小计", "订单产品", "付款状态"),
                List.of(sourceNo + "-卡卡龙球咖旗舰店", sourceNo, "2026-08-06 22:29:28",
                        "卡卡龙球咖旗舰店", "林晨旭", "2026/08/06", "4", "¥0.00",
                        "油泼辣子拌面,金汤肥牛", "未付款")));

        service.preflight(caller(), file, null);
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.projectedRows()).isEqualTo(1);
        assertThat(capturedOrder.get()).satisfies(command ->
                assertThat(command.lines()).singleElement().satisfies(line -> {
                    assertThat(line.productNameSnapshot()).isEqualTo("油泼辣子拌面,金汤肥牛");
                    assertThat(line.quantity()).isEqualByComparingTo("4");
                    assertThat(line.unitPrice()).isEqualByComparingTo("0");
                    assertThat(line.discountAmount()).isNull();
                }));
    }

    @Test
    void runUsesSalesOrderSubtotalWhenDetailLinesKeepOriginalGoodsAmount() {
        CapturingStore store = new CapturingStore();
        AtomicReference<SalesOrderCommand> capturedOrder = new AtomicReference<>();
        String sourceNo = "DD202608062285";
        SalesOrderDetailView staleOrder = salesOrderDetail(9001L, sourceNo,
                new BigDecimal("4"), new BigDecimal("312"), BigDecimal.ZERO, new BigDecimal("312"),
                List.of(
                        salesOrderLine(1L, 2001L, 3001L, "油泼辣子拌面", new BigDecimal("2"),
                                new BigDecimal("78"), BigDecimal.ZERO),
                        salesOrderLine(2L, 2002L, 3002L, "金汤肥牛", new BigDecimal("2"),
                                new BigDecimal("78"), BigDecimal.ZERO)));
        ErpProductProjectionClient erpClient = new ErpProductProjectionClient() {
            @Override
            public ExternalProductSyncResult sync(CallerIdentity caller, String sourceSystem,
                                                  List<ExternalProductRowCommand> rows) {
                return new ExternalProductSyncResult(0, 0, 0, 0, 0, List.of(), List.of());
            }

            @Override
            public List<ExternalProductResolvedView> resolve(CallerIdentity caller, String preferredSourceSystem,
                                                             List<ExternalProductResolveRowCommand> rows) {
                return rows.stream().map(row -> {
                    boolean spicyNoodle = row.productName() != null && row.productName().contains("油泼");
                    return new ExternalProductResolvedView(row.referenceId(),
                            spicyNoodle ? 2001L : 2002L,
                            spicyNoodle ? 3001L : 3002L,
                            spicyNoodle ? "PRD202608060001" : "PRD202608060002",
                            spicyNoodle ? "SKU202608060001" : "SKU202608060002",
                            spicyNoodle ? "油泼辣子拌面" : "金汤肥牛",
                            "默认规格", "BOX", "DINGHUOBAO",
                            "PRODUCT_NAME_FUZZY_UNIQUE", 70, "MATCHED", "已匹配");
                }).toList();
            }
        };
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.of(staleOrder), salesOrderDetail(9001L, sourceNo), capturedOrder),
                null, null, erpClient, null);
        MockMultipartFile file = xlsx("飞书全量.xlsx", List.of(
                new SheetSource("销售订单表", List.of(
                        List.of("订单编号门店", "订单编号", "创建时间", "门店", "销售",
                                "销售日期", "数量", "小计", "实际小计", "订单产品", "付款状态"),
                        List.of(sourceNo + "-卡卡龙球咖旗舰店", sourceNo, "2026-08-06 22:29:28",
                                "卡卡龙球咖旗舰店", "林晨旭", "2026/08/06", "4",
                                "¥0.00", "¥0.00", "油泼辣子拌面,金汤肥牛", "未付款"))),
                new SheetSource("订单明细表", List.of(
                        List.of("订单明细号", "关联订单", "订单产品", "数量(箱)", "设计单价", "实际小计"),
                        List.of("ODD202608064909", sourceNo + "-卡卡龙球咖旗舰店",
                                "油泼辣子拌面", "2", "78", "¥156.00"),
                        List.of("ODD202608064910", sourceNo + "-卡卡龙球咖旗舰店",
                                "金汤肥牛", "2", "78", "¥156.00")))));

        service.preflight(caller(), file, null);
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.projectedRows()).isEqualTo(3);
        assertThat(capturedOrder.get()).satisfies(command -> {
            assertThat(command.discountAmount()).isEqualByComparingTo("312.00");
            assertThat(command.remark()).contains("实际小计0.00").contains("订单明细合计312.00");
            assertThat(command.lines()).hasSize(2);
            assertThat(command.lines()).allSatisfy(line -> {
                assertThat(line.unitPrice()).isEqualByComparingTo("78");
                assertThat(line.discountAmount()).isNull();
            });
        });
    }

    @Test
    void runUsesSalesOrderSubtotalWhenHeaderRoundsUpOneCent() {
        CapturingStore store = new CapturingStore();
        AtomicReference<SalesOrderCommand> capturedOrder = new AtomicReference<>();
        String sourceNo = "DD202608253130";
        SalesOrderDetailView staleOrder = salesOrderDetail(9001L, sourceNo,
                new BigDecimal("3"), new BigDecimal("222.30"), BigDecimal.ZERO, new BigDecimal("211.18"),
                List.of(salesOrderLine(1L, 2001L, 3001L, "油泼辣子拌面", new BigDecimal("3"),
                        new BigDecimal("74.10"), new BigDecimal("11.12"))));
        ErpProductProjectionClient erpClient = new ErpProductProjectionClient() {
            @Override
            public ExternalProductSyncResult sync(CallerIdentity caller, String sourceSystem,
                                                  List<ExternalProductRowCommand> rows) {
                return new ExternalProductSyncResult(0, 0, 0, 0, 0, List.of(), List.of());
            }

            @Override
            public List<ExternalProductResolvedView> resolve(CallerIdentity caller, String preferredSourceSystem,
                                                             List<ExternalProductResolveRowCommand> rows) {
                return rows.stream()
                        .map(row -> new ExternalProductResolvedView(row.referenceId(),
                                2001L, 3001L, "PRD202608250001", "SKU202608250001",
                                "油泼辣子拌面", "默认规格", "BOX", "DINGHUOBAO",
                                "PRODUCT_NAME_FUZZY_UNIQUE", 70, "MATCHED", "已匹配"))
                        .toList();
            }
        };
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.of(staleOrder), salesOrderDetail(9001L, sourceNo), capturedOrder),
                null, null, erpClient, null);
        MockMultipartFile file = xlsx("飞书全量.xlsx", List.of(
                new SheetSource("销售订单表", List.of(
                        List.of("订单编号", "创建时间", "客户ID", "门店", "销售",
                                "销售日期", "数量", "实际小计", "待付金额", "订单产品", "付款状态"),
                        List.of(sourceNo, "2026-08-25 10:00:00", "1001", "名仕台球光谷店",
                                "陈益琼", "2026/08/25", "3", "¥211.19", "¥211.19",
                                "油泼辣子拌面,金汤肥牛", "未付款"))),
                new SheetSource("订单明细表", List.of(
                        List.of("订单明细号", "关联订单", "订单产品", "数量(箱)", "设计单价", "实际小计"),
                        List.of("ODD202608253130001", sourceNo + "-名仕台球光谷店",
                                "油泼辣子拌面", "3", "74.10", "¥211.18")))));

        service.preflight(caller(), file, null);
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.projectedRows()).isEqualTo(2);
        assertThat(capturedOrder.get()).satisfies(command -> {
            assertThat(command.sourceUnpaidAmount()).isEqualByComparingTo("211.19");
            assertThat(command.discountAmount()).isNull();
            assertThat(command.remark()).contains("实际小计211.19").contains("订单明细合计211.18");
            assertThat(command.lines()).singleElement().satisfies(line -> {
                assertThat(line.unitPrice()).isEqualByComparingTo("74.10");
                assertThat(line.discountAmount()).isEqualByComparingTo("11.11");
            });
        });
    }

    @Test
    void preflightKeepsUnchangedHistoricalDuplicateRowsRunnable() {
        CapturingStore store = new CapturingStore();
        FeishuImportBundleService service = service(store);
        MockMultipartFile file = xlsx("销售人员信息.xlsx", "渡江战役团队管理", List.of(
                List.of("销售姓名", "姓名", "岗位", "职位", "销售区域", "销售leader", "城市", "手机号",
                        "入职日期", "在职状态"),
                List.of("2026-06-24-李嘉豪", "李嘉豪", "销售", "城市总", "华北地区",
                        "张海龙", "西安", "13800000001", "46197", "在职")));

        service.preflight(caller(), file, null);
        PreflightRawRow first = store.saved.rawRows().getFirst();
        store.existingDeduplicationRows.put(first.deduplicationKey(),
                new ExistingDeduplicationRow(first.deduplicationKey(), first.id(),
                        first.rowHash(), "PROJECTED", "HR", "EMPLOYEE", "EMP202606240001"));

        FeishuImportPreflightResult repeated = service.preflight(caller(), file, null);

        assertThat(repeated.duplicateRows()).isEqualTo(1);
        assertThat(repeated.issues()).anySatisfy(issue -> {
            assertThat(issue.severity()).isEqualTo("INFO");
            assertThat(issue.issueType()).isEqualTo("FEISHU_DUPLICATE_UNCHANGED");
            assertThat(issue.message()).contains("历史重复会重新校验业务库");
        });
        assertThat(store.saved.rawRows()).singleElement().satisfies(row -> {
            assertThat(row.importStatus()).isEqualTo("IMPORTED");
            assertThat(row.projectionStatus()).isEqualTo("PENDING");
            assertThat(row.duplicateScope()).isEqualTo("HISTORY_UNCHANGED");
        });
    }

    @Test
    void preflightDropsUnchangedCurrentBatchDuplicateRows() {
        CapturingStore store = new CapturingStore();
        FeishuImportBundleService service = service(store);
        MockMultipartFile file = xlsx("销售人员信息.xlsx", "渡江战役团队管理", List.of(
                List.of("销售姓名", "姓名", "岗位", "职位", "销售区域", "销售leader", "城市", "手机号",
                        "入职日期", "在职状态"),
                List.of("2026-06-24-李嘉豪", "李嘉豪", "销售", "城市总", "华北地区",
                        "张海龙", "西安", "13800000001", "46197", "在职"),
                List.of("2026-06-24-李嘉豪", "李嘉豪", "销售", "城市总", "华北地区",
                        "张海龙", "西安", "13800000001", "46197", "在职")));

        FeishuImportPreflightResult result = service.preflight(caller(), file, null);

        assertThat(result.duplicateRows()).isEqualTo(1);
        assertThat(store.saved.rawRows()).hasSize(2);
        assertThat(store.saved.rawRows()).anySatisfy(row -> {
            assertThat(row.importStatus()).isEqualTo("IMPORTED");
            assertThat(row.projectionStatus()).isEqualTo("PENDING");
            assertThat(row.duplicateScope()).isNull();
        });
        assertThat(store.saved.rawRows()).anySatisfy(row -> {
            assertThat(row.importStatus()).isEqualTo("DROPPED");
            assertThat(row.projectionStatus()).isEqualTo("SKIPPED");
            assertThat(row.duplicateScope()).isEqualTo("BATCH_UNCHANGED");
        });
    }

    @Test
    void runStatusCountsDroppedCurrentBatchDuplicateRowsAsSkipped() {
        CapturingStore store = new CapturingStore();
        FeishuImportBundleService service = service(store);
        MockMultipartFile file = xlsx("销售人员信息.xlsx", "渡江战役团队管理", List.of(
                List.of("销售姓名", "姓名", "岗位", "职位", "销售区域", "销售leader", "城市", "手机号",
                        "入职日期", "在职状态"),
                List.of("2026-06-24-李嘉豪", "李嘉豪", "销售", "城市总", "华北地区",
                        "张海龙", "西安", "13800000001", "46197", "在职"),
                List.of("2026-06-24-李嘉豪", "李嘉豪", "销售", "城市总", "华北地区",
                        "张海龙", "西安", "13800000001", "46197", "在职")));

        service.preflight(caller(), file, null);
        FeishuImportRunResult status = service.runStatus(caller(), store.saved.id(), 10);

        assertThat(status.totalRows()).isEqualTo(2);
        assertThat(status.waitingMappingRows()).isEqualTo(1);
        assertThat(status.skippedRows()).isEqualTo(1);
        assertThat(status.rows()).extracting("projectionStatus")
                .containsExactly("PENDING", "SKIPPED");
    }

    @Test
    void preflightKeepsUnfinishedHistoricalRowsRunnable() {
        CapturingStore store = new CapturingStore();
        FeishuImportBundleService service = service(store);
        MockMultipartFile file = xlsx("销售人员信息.xlsx", "渡江战役团队管理", List.of(
                List.of("销售姓名", "姓名", "岗位", "职位", "销售区域", "销售leader", "城市", "手机号",
                        "入职日期", "在职状态"),
                List.of("2026-06-24-李嘉豪", "李嘉豪", "销售", "城市总", "华北地区",
                        "张海龙", "西安", "13800000001", "46197", "在职")));

        service.preflight(caller(), file, null);
        PreflightRawRow first = store.saved.rawRows().getFirst();
        store.existingDeduplicationRows.put(first.deduplicationKey(),
                new ExistingDeduplicationRow(first.deduplicationKey(), first.id(),
                        first.rowHash(), "PENDING", "HR", "EMPLOYEE", null));

        FeishuImportPreflightResult repeated = service.preflight(caller(), file, null);

        assertThat(repeated.duplicateRows()).isZero();
        assertThat(repeated.issues()).noneSatisfy(issue ->
                assertThat(issue.issueType()).startsWith("FEISHU_DUPLICATE"));
        assertThat(store.saved.rawRows()).singleElement().satisfies(row -> {
            assertThat(row.importStatus()).isEqualTo("IMPORTED");
            assertThat(row.projectionStatus()).isEqualTo("PENDING");
            assertThat(row.duplicateScope()).isNull();
        });
    }

    @Test
    void changedHistoricalDuplicateUpdatesExistingSalesOrder() {
        CapturingStore store = new CapturingStore();
        String sourceNo = "XS.20260901.0001";
        FeishuImportBundleService preflightService = service(store);
        MockMultipartFile original = xlsx("销售订单.xlsx", "销售订单表", List.of(
                List.of("订单编号", "创建时间", "客户ID", "商品ID", "规格ID", "单位编码",
                        "数量", "单价", "门店", "订单产品", "销售"),
                List.of(sourceNo, "2026-09-01 10:00:00", "1001", "2001", "3001", "BOX",
                        "2", "18.50", "武汉门店", "鲜榨果汁", "张三")));
        preflightService.preflight(caller(), original, null);
        PreflightRawRow first = store.saved.rawRows().getFirst();
        store.existingDeduplicationRows.put(first.deduplicationKey(),
                new ExistingDeduplicationRow(first.deduplicationKey(), first.id(),
                        first.rowHash(), "PROJECTED", "ORDER", "SALES_ORDER", "9001"));

        AtomicReference<SalesOrderCommand> captured = new AtomicReference<>();
        SalesOrderDetailView existing = salesOrderDetail(9001L, sourceNo);
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.of(existing), salesOrderDetail(9001L, sourceNo), captured));
        MockMultipartFile changed = xlsx("销售订单.xlsx", "销售订单表", List.of(
                List.of("订单编号", "创建时间", "客户ID", "商品ID", "规格ID", "单位编码",
                        "数量", "单价", "门店", "订单产品", "销售"),
                List.of(sourceNo, "2026-09-01 10:00:00", "1001", "2001", "3001", "BOX",
                        "3", "20.00", "武汉门店", "鲜榨果汁", "张三")));

        FeishuImportPreflightResult preflight = service.preflight(caller(), changed, null);
        FeishuImportRunResult run = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(preflight.duplicateRows()).isEqualTo(1);
        assertThat(preflight.issues()).anySatisfy(issue -> {
            assertThat(issue.severity()).isEqualTo("WARN");
            assertThat(issue.issueType()).isEqualTo("FEISHU_DUPLICATE_CHANGED");
            assertThat(issue.message()).contains("更新业务数据");
        });
        assertThat(run.status()).isEqualTo("SUCCEEDED");
        assertThat(run.projectedRows()).isEqualTo(1);
        assertThat(run.rows()).singleElement().satisfies(row -> {
            assertThat(row.projectionStatus()).isEqualTo("PROJECTED");
            assertThat(row.message()).contains("已按本次导入内容更新");
        });
        assertThat(captured.get()).satisfies(command -> {
            assertThat(command.revision()).isEqualTo(existing.revision());
            assertThat(command.lines()).singleElement().satisfies(line -> {
                assertThat(line.quantity()).isEqualByComparingTo("3");
                assertThat(line.unitPrice()).isEqualByComparingTo("20.00");
            });
        });
    }

    @Test
    void runResolvesSalesOrderMappingsFromSameWorkbookMasterData() {
        CapturingStore store = new CapturingStore();
        AtomicReference<SalesOrderCommand> capturedOrder = new AtomicReference<>();
        String sourceNo = "XS.20260901.0001";
        CrmCustomerProjectionClient crmClient = new CrmCustomerProjectionClient() {
            @Override
            public ExternalCrmAreaSyncResult syncAreas(CallerIdentity caller, String sourceSystem,
                                                       List<ExternalCrmAreaRowCommand> rows) {
                return new ExternalCrmAreaSyncResult(rows.size(), 0, 0, rows.size(), 0,
                        rows.stream()
                                .map(row -> new ExternalCrmAreaSyncRowResult(
                                        row.sourceAreaId(), "REGION", "CITY", "UNCHANGED", "已存在"))
                                .toList(),
                        List.of());
            }

            @Override
            public ExternalCrmCustomerSyncResult sync(CallerIdentity caller, String sourceSystem,
                                                      List<ExternalCrmCustomerRowCommand> rows) {
                return new ExternalCrmCustomerSyncResult(rows.size(), rows.size(), 0, 0, 0,
                        rows.stream()
                                .map(row -> new ExternalCrmCustomerSyncRowResult(
                                        row.sourceCustomerId(), 1001L, "C202609010001",
                                        "CREATED", "CRM客户已创建"))
                                .toList(),
                        List.of());
            }
        };
        ErpProductProjectionClient erpClient = (caller, sourceSystem, rows) ->
                new ExternalProductSyncResult(rows.size(), rows.size(), 0, 0, 0,
                        rows.stream()
                                .map(row -> new ExternalProductSyncRowResult(row.sourceProductId(),
                                        2001L, 3001L, "PRD202609010001", "SKU202609010001",
                                        row.unitCode(), "CREATED", "ERP商品已创建"))
                                .toList(),
                        List.of());
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, sourceNo), capturedOrder),
                null, crmClient, erpClient, null);
        MockMultipartFile file = xlsx("飞书全量.xlsx", List.of(
                new SheetSource("销售订单表", List.of(
                        List.of("订单编号", "创建时间", "城市", "关联门店", "订单产品", "数量", "实际小计", "销售"),
                        List.of(sourceNo, "2026-09-01 10:00:00", "武汉", "SP1001 - 武汉门店",
                                "酸辣粉", "2", "37.00", "张三"))),
                new SheetSource("门店信息库", List.of(
                        List.of("门店编码", "门店名称", "城市", "门店状态", "销售", "创建时间"),
                        List.of("SP1001", "武汉门店", "武汉", "营业中", "张三",
                                "2026-08-31 10:00:00"))),
                new SheetSource("产品信息库", List.of(
                        List.of("产品编码", "产品名称", "业务线", "品牌", "行业", "品类", "定价", "规格", "状态", "创建时间"),
                        List.of("P1001", "酸辣粉", "零售业务", "瑞盖", "食品", "粉面",
                                "18.50", "箱", "在售", "2026-08-30 10:00:00")))));

        service.preflight(caller(), file, null);
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.projectedRows()).isEqualTo(3);
        assertThat(result.waitingMappingRows()).isZero();
        assertThat(capturedOrder.get()).satisfies(command -> {
            assertThat(command.customerId()).isEqualTo(1001L);
            assertThat(command.customerCodeSnapshot()).isEqualTo("C202609010001");
            assertThat(command.regionCode()).isEqualTo("CITY");
            assertThat(command.lines()).singleElement().satisfies(line -> {
                assertThat(line.productId()).isEqualTo(2001L);
                assertThat(line.productVariantId()).isEqualTo(3001L);
                assertThat(line.productCodeSnapshot()).isEqualTo("PRD202609010001");
                assertThat(line.skuCodeSnapshot()).isEqualTo("SKU202609010001");
                assertThat(line.unitCode()).isEqualTo("BOX");
                assertThat(line.unitPrice()).isEqualByComparingTo("18.500000");
            });
        });
    }

    @Test
    void runResolvesSalesOrderOwnerEmployeeCodeFromHr() {
        CapturingStore store = new CapturingStore();
        AtomicReference<SalesOrderCommand> capturedOrder = new AtomicReference<>();
        AtomicReference<ExternalEmployeeResolveCommand> capturedEmployeeResolve = new AtomicReference<>();
        AtomicReference<List<ExternalCrmCustomerRowCommand>> capturedCrmRows = new AtomicReference<>();
        String sourceNo = "DD202609013377";
        HrEmployeeProjectionClient hrClient = new HrEmployeeProjectionClient() {
            @Override
            public ExternalEmployeeSyncResult sync(CallerIdentity caller, String sourceSystem,
                                                   List<ExternalEmployeeRowCommand> rows) {
                return new ExternalEmployeeSyncResult(0, 0, 0, 0, 0, List.of(), List.of());
            }

            @Override
            public List<ExternalEmployeeResolvedView> resolve(
                    CallerIdentity caller, ExternalEmployeeResolveCommand command) {
                capturedEmployeeResolve.set(command);
                return List.of(new ExternalEmployeeResolvedView("FEISHU", "FEISHU_SALES_STAFF",
                        null, 501L, "EMP202605179671", "刘鹏昆", "ACTIVE",
                        "销售", null, "销售员", null, null, null, null, "上海", NOW));
            }
        };
        CrmCustomerProjectionClient crmClient = new CrmCustomerProjectionClient() {
            @Override
            public ExternalCrmAreaSyncResult syncAreas(CallerIdentity caller, String sourceSystem,
                                                       List<ExternalCrmAreaRowCommand> rows) {
                return new ExternalCrmAreaSyncResult(0, 0, 0, 0, 0, List.of(), List.of());
            }

            @Override
            public ExternalCrmCustomerSyncResult sync(CallerIdentity caller, String sourceSystem,
                                                      List<ExternalCrmCustomerRowCommand> rows) {
                capturedCrmRows.set(List.copyOf(rows));
                return new ExternalCrmCustomerSyncResult(rows.size(), rows.size(), 0, 0, 0,
                        rows.stream()
                                .map(row -> new ExternalCrmCustomerSyncRowResult(
                                        row.sourceCustomerId(), 1001L, "C202609010001",
                                        "CREATED", "CRM客户已创建"))
                                .toList(),
                        List.of());
            }
        };
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, sourceNo), capturedOrder),
                hrClient, crmClient, null, null);
        MockMultipartFile file = xlsx("销售订单.xlsx", "销售订单表", List.of(
                List.of("订单编号", "创建时间", "关联门店", "门店", "销售", "客户ID",
                        "商品ID", "规格ID", "单位编码", "数量", "单价"),
                List.of(sourceNo, "2026-09-01 10:00:00", "SP1001 - 武汉门店", "武汉门店",
                        "刘鹏昆", "1001", "2001", "3001", "BOX", "2", "18.50")));

        service.preflight(caller(), file, null);
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(capturedEmployeeResolve.get()).satisfies(command -> {
            assertThat(command.sourceTenantKey()).isEqualTo("FEISHU_SALES_STAFF");
            assertThat(command.employeeNames()).containsExactly("刘鹏昆");
        });
        assertThat(capturedCrmRows.get()).singleElement().satisfies(row -> {
            assertThat(row.ownerEmployeeCode()).isEqualTo("EMP202605179671");
            assertThat(row.ownerEmployeeNameSnapshot()).isEqualTo("刘鹏昆");
        });
        assertThat(capturedOrder.get()).satisfies(command -> {
            assertThat(command.ownerSalesName()).isEqualTo("刘鹏昆");
            assertThat(command.ownerEmployeeCode()).isEqualTo("EMP202605179671");
            assertThat(command.ownerEmployeeNameSnapshot()).isEqualTo("刘鹏昆");
        });
    }

    @Test
    void runProjectsSalesOrderLinesByOrderHeaderAndMergesDuplicateProductVariants() {
        CapturingStore store = new CapturingStore();
        AtomicReference<SalesOrderCommand> capturedOrder = new AtomicReference<>();
        String sourceNo = "DD202609013380";
        CrmCustomerProjectionClient crmClient = new CrmCustomerProjectionClient() {
            @Override
            public ExternalCrmAreaSyncResult syncAreas(CallerIdentity caller, String sourceSystem,
                                                       List<ExternalCrmAreaRowCommand> rows) {
                return new ExternalCrmAreaSyncResult(rows.size(), 0, 0, rows.size(), 0,
                        rows.stream()
                                .map(row -> new ExternalCrmAreaSyncRowResult(
                                        row.sourceAreaId(), "REGION", "CITY", "UNCHANGED", "已存在"))
                                .toList(),
                        List.of());
            }

            @Override
            public ExternalCrmCustomerSyncResult sync(CallerIdentity caller, String sourceSystem,
                                                      List<ExternalCrmCustomerRowCommand> rows) {
                return new ExternalCrmCustomerSyncResult(rows.size(), rows.size(), 0, 0, 0,
                        rows.stream()
                                .map(row -> new ExternalCrmCustomerSyncRowResult(
                                        row.sourceCustomerId(), 1001L, "C202609010001",
                                        "CREATED", "CRM客户已创建"))
                                .toList(),
                        List.of());
            }
        };
        ErpProductProjectionClient erpClient = (caller, sourceSystem, rows) ->
                new ExternalProductSyncResult(rows.size(), rows.size(), 0, 0, 0,
                        rows.stream()
                                .map(row -> new ExternalProductSyncRowResult(row.sourceProductId(),
                                        2001L, 3001L, "PRD202609010001", "SKU202609010001",
                                        row.unitCode(), "CREATED", "ERP商品已创建"))
                                .toList(),
                        List.of());
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, sourceNo), capturedOrder),
                null, crmClient, erpClient, null);
        MockMultipartFile file = xlsx("飞书全量.xlsx", List.of(
                new SheetSource("门店信息库", List.of(
                        List.of("门店编码", "门店名称", "城市", "门店状态", "销售", "创建时间"),
                        List.of("SP1001", "武汉门店", "武汉", "营业中", "张三",
                                "2026-08-31 10:00:00"))),
                new SheetSource("产品信息库", List.of(
                        List.of("产品编码", "产品名称", "业务线", "品牌", "行业", "品类", "定价", "规格", "状态", "创建时间"),
                        List.of("P1001", "酸辣粉", "零售业务", "瑞盖", "食品", "粉面",
                                "18.50", "箱", "在售", "2026-08-30 10:00:00"))),
                new SheetSource("销售订单表", List.of(
                        List.of("订单编号门店", "创建时间", "关联门店", "销售"),
                        List.of(sourceNo + "-武汉门店", "2026-09-01 10:00:00",
                                "SP1001 - 武汉门店", "张三"))),
                new SheetSource("订单明细表", List.of(
                        List.of("订单明细号", "订单编号", "产品编码", "订单产品", "规格", "数量", "实际小计"),
                        List.of("ODD202609013380001", sourceNo, "P1001", "酸辣粉", "箱", "2", "37.00"),
                        List.of("ODD202609013380002", sourceNo, "P1001", "酸辣粉", "箱", "3", "55.50")))));

        service.preflight(caller(), file, null);
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.projectedRows()).isEqualTo(5);
        assertThat(result.waitingMappingRows()).isZero();
        assertThat(result.failedRows()).isZero();
        assertThat(result.rows()).filteredOn(row -> "SALES_ORDER_LINE".equals(row.targetObjectType()))
                .hasSize(2)
                .allSatisfy(row -> {
                    assertThat(row.projectionStatus()).isEqualTo("PROJECTED");
                    assertThat(row.targetId()).isEqualTo("9001");
                    assertThat(row.message()).contains("订单明细已随销售订单导入Order");
                });
        assertThat(capturedOrder.get()).satisfies(command -> {
            assertThat(command.sourceOrderNo()).isEqualTo(sourceNo);
            assertThat(command.orderDate()).isEqualTo(Instant.parse("2026-09-01T02:00:00Z"));
            assertThat(command.lines()).singleElement().satisfies(line -> {
                assertThat(line.productId()).isEqualTo(2001L);
                assertThat(line.productVariantId()).isEqualTo(3001L);
                assertThat(line.quantity()).isEqualByComparingTo("5");
                assertThat(line.unitPrice()).isEqualByComparingTo("18.500000");
            });
        });
        assertThat(store.projectionUpdates).filteredOn(update -> "SALES_ORDER_LINE".equals(update.targetObjectType()))
                .hasSize(2);
        assertThat(store.batchStatus).isEqualTo("SUCCEEDED");
    }

    @Test
    void runResolvesSalesOrderLineProductFromDhbErpProductByDescriptorNameAcrossFiles() {
        CapturingStore store = new CapturingStore();
        AtomicReference<SalesOrderCommand> capturedOrder = new AtomicReference<>();
        AtomicReference<List<ExternalCrmCustomerRowCommand>> capturedCrmRows = new AtomicReference<>();
        AtomicReference<String> capturedPreferredSource = new AtomicReference<>();
        AtomicReference<List<ExternalProductResolveRowCommand>> capturedResolveRows = new AtomicReference<>();
        String sourceNo = "DD202609013390";
        CrmCustomerProjectionClient crmClient = new CrmCustomerProjectionClient() {
            @Override
            public ExternalCrmAreaSyncResult syncAreas(CallerIdentity caller, String sourceSystem,
                                                       List<ExternalCrmAreaRowCommand> rows) {
                return new ExternalCrmAreaSyncResult(rows.size(), 0, 0, rows.size(), 0,
                        rows.stream()
                                .map(row -> new ExternalCrmAreaSyncRowResult(
                                        row.sourceAreaId(), "REGION", "CITY", "UNCHANGED", "已存在"))
                                .toList(),
                        List.of());
            }

            @Override
            public ExternalCrmCustomerSyncResult sync(CallerIdentity caller, String sourceSystem,
                                                      List<ExternalCrmCustomerRowCommand> rows) {
                capturedCrmRows.set(List.copyOf(rows));
                return new ExternalCrmCustomerSyncResult(rows.size(), rows.size(), 0, 0, 0,
                        rows.stream()
                                .map(row -> new ExternalCrmCustomerSyncRowResult(
                                        row.sourceCustomerId(), 1001L, "C202609010001",
                                        "CREATED", "CRM客户已创建"))
                                .toList(),
                        List.of());
            }
        };
        ErpProductProjectionClient erpClient = new ErpProductProjectionClient() {
            @Override
            public ExternalProductSyncResult sync(CallerIdentity caller, String sourceSystem,
                                                  List<ExternalProductRowCommand> rows) {
                return new ExternalProductSyncResult(0, 0, 0, 0, 0, List.of(), List.of());
            }

            @Override
            public List<ExternalProductResolvedView> resolve(CallerIdentity caller, String preferredSourceSystem,
                                                             List<ExternalProductResolveRowCommand> rows) {
                capturedPreferredSource.set(preferredSourceSystem);
                capturedResolveRows.set(List.copyOf(rows));
                return rows.stream()
                        .map(row -> new ExternalProductResolvedView(row.referenceId(),
                                2001L, 3001L, "PRD202609010001", "SKU202609010001",
                                "油泼辣子拌面", "默认规格", "BOX", "DINGHUOBAO",
                                "PRODUCT_NAME_EXACT", 95, "MATCHED", "已匹配"))
                        .toList();
            }
        };
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, sourceNo), capturedOrder),
                null, crmClient, erpClient, null);
        MockMultipartFile orderFile = xlsx("全国销售订单列表.xlsx", "销售订单表", List.of(
                List.of("订单编号门店", "订单编号", "关联门店", "门店", "城市", "销售", "销售日期",
                        "数量", "实际小计", "付款状态", "创建时间"),
                List.of(sourceNo + "-A8台球俱乐部", sourceNo, "SP1692 - A8台球俱乐部",
                        "A8台球俱乐部", "上海", "刘鹏昆", "2026-09-01", "3",
                        "¥234.00", "未付款", "2026-09-01 10:00:00")));
        MockMultipartFile lineFile = xlsx("订单明细列表.xlsx", "订单明细表", List.of(
                List.of("订单明细号", "关联订单", "产品编号", "数量(箱)", "实际小计", "创建时间"),
                List.of("ODD202609013390001", sourceNo + "-A8台球俱乐部",
                        "杨掌柜-油泼辣子拌面-12桶/箱", "3", "¥234.00",
                        "2026-09-01 10:01:00")));

        FeishuImportPreflightResult preflight = service.preflight(caller(), List.of(orderFile, lineFile), null);
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(preflight.totalSheets()).isEqualTo(2);
        assertThat(preflight.totalRows()).isEqualTo(2);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.projectedRows()).isEqualTo(2);
        assertThat(result.waitingMappingRows()).isZero();
        assertThat(capturedCrmRows.get()).singleElement().satisfies(row -> {
            assertThat(row.sourceCustomerId()).isEqualTo("SP1692");
            assertThat(row.customerName()).isEqualTo("A8台球俱乐部");
            assertThat(row.cityName()).isEqualTo("上海");
            assertThat(row.ownerEmployeeNameSnapshot()).isEqualTo("刘鹏昆");
        });
        assertThat(capturedPreferredSource.get()).isEqualTo("DINGHUOBAO");
        assertThat(capturedResolveRows.get()).singleElement().satisfies(row -> {
            assertThat(row.productCode()).isNull();
            assertThat(row.productName()).isEqualTo("油泼辣子拌面");
            assertThat(row.specification()).isNull();
        });
        assertThat(capturedOrder.get()).satisfies(command -> {
            assertThat(command.customerId()).isEqualTo(1001L);
            assertThat(command.customerCodeSnapshot()).isEqualTo("C202609010001");
            assertThat(command.customerNameSnapshot()).isEqualTo("A8台球俱乐部");
            assertThat(command.lines()).singleElement().satisfies(line -> {
                assertThat(line.productId()).isEqualTo(2001L);
                assertThat(line.productVariantId()).isEqualTo(3001L);
                assertThat(line.productCodeSnapshot()).isEqualTo("PRD202609010001");
                assertThat(line.skuCodeSnapshot()).isEqualTo("SKU202609010001");
                assertThat(line.productNameSnapshot()).isEqualTo("油泼辣子拌面");
                assertThat(line.specificationSnapshot()).isEqualTo("12桶/箱");
                assertThat(line.unitCode()).isEqualTo("BOX");
            });
        });
    }

    @Test
    void dryRunValidSalesStaffDoesNotCallHr() {
        CapturingStore store = new CapturingStore();
        AtomicReference<List<ExternalEmployeeRowCommand>> captured = new AtomicReference<>();
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, "DEFAULT"), new AtomicReference<>()),
                (caller, sourceSystem, rows) -> {
                    captured.set(rows);
                    throw new AssertionError("dry run must not call HR");
                });
        MockMultipartFile file = xlsx("销售人员信息.xlsx", "渡江战役团队管理", List.of(
                List.of("销售姓名", "姓名", "岗位", "职位", "销售区域", "销售leader", "城市", "手机号",
                        "入职日期", "在职状态"),
                List.of("2026-06-24-李嘉豪", "李嘉豪", "销售", "城市总", "华北地区",
                        "张海龙", "西安", "", "46197", "在职")));

        service.preflight(caller(), file, null);
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, true));

        assertThat(captured.get()).isNull();
        assertThat(result.status()).isEqualTo("DRY_RUN");
        assertThat(result.skippedRows()).isEqualTo(1);
        assertThat(result.waitingMappingRows()).isZero();
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.projectionStatus()).isEqualTo("SKIPPED");
            assertThat(row.message()).contains("正式写入时会导入HR员工");
        });
        assertThat(store.projectionUpdates).isEmpty();
    }

    @Test
    void runProjectsSalesStaffToHrWhenWrite() {
        CapturingStore store = new CapturingStore();
        AtomicReference<CallerIdentity> capturedCaller = new AtomicReference<>();
        AtomicReference<String> capturedSourceSystem = new AtomicReference<>();
        AtomicReference<List<ExternalEmployeeRowCommand>> capturedRows = new AtomicReference<>();
        HrEmployeeProjectionClient hrClient = (caller, sourceSystem, rows) -> {
            capturedCaller.set(caller);
            capturedSourceSystem.set(sourceSystem);
            capturedRows.set(rows);
            return new ExternalEmployeeSyncResult(1, 1, 0, 0, 0,
                    List.of(new ExternalEmployeeSyncRowResult("2026-06-24-李嘉豪",
                            "EMP202606240001", "CREATED", "HR员工已创建")),
                    List.of());
        };
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, "DEFAULT"), new AtomicReference<>()),
                hrClient);
        MockMultipartFile file = xlsx("销售人员信息.xlsx", "渡江战役团队管理", List.of(
                List.of("销售姓名", "姓名", "岗位", "职位", "销售区域", "销售leader", "城市", "手机号",
                        "入职日期", "在职状态"),
                List.of("2026-06-24-李嘉豪", "李嘉豪", "销售", "城市总", "华北地区",
                        "张海龙", "西安", "", "46197", "在职")));

        service.preflight(caller(), file, null);
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.projectedRows()).isEqualTo(1);
        assertThat(result.waitingMappingRows()).isZero();
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.projectionStatus()).isEqualTo("PROJECTED");
            assertThat(row.targetDomain()).isEqualTo("HR");
            assertThat(row.targetObjectType()).isEqualTo("EMPLOYEE");
            assertThat(row.targetId()).isEqualTo("EMP202606240001");
        });
        assertThat(capturedCaller.get().principalScope()).isEqualTo("SERVICE");
        assertThat(capturedCaller.get().tenantId()).isEqualTo(TENANT_ID);
        assertThat(capturedCaller.get().permissions()).contains("hr:employee:sync");
        assertThat(capturedCaller.get().permissions()).contains("hr:employee:read");
        assertThat(capturedSourceSystem.get()).isEqualTo("FEISHU");
        assertThat(capturedRows.get()).singleElement().satisfies(row -> {
            assertThat(row.sourceTenantKey()).isEqualTo("FEISHU_SALES_STAFF");
            assertThat(row.sourceEmployeeId()).isEqualTo("2026-06-24-李嘉豪");
            assertThat(row.employeeName()).isEqualTo("李嘉豪");
            assertThat(row.jobCategory()).isEqualTo("销售");
            assertThat(row.positionName()).isEqualTo("城市总");
            assertThat(row.regionName()).isEqualTo("华北地区");
            assertThat(row.cityName()).isEqualTo("西安");
            assertThat(row.employmentStatus()).isEqualTo("在职");
        });
        assertThat(store.projectionUpdates).hasSize(1);
        assertThat(store.batchStatus).isEqualTo("SUCCEEDED");
    }

    @Test
    void formalRunUploadsFeishuAttachmentsToCosBeforeProjection() {
        CapturingStore store = new CapturingStore();
        AtomicReference<List<ExternalEmployeeRowCommand>> capturedRows = new AtomicReference<>();
        HrEmployeeProjectionClient hrClient = (caller, sourceSystem, rows) -> {
            capturedRows.set(rows);
            return new ExternalEmployeeSyncResult(1, 1, 0, 0, 0,
                    List.of(new ExternalEmployeeSyncRowResult("2026-06-24-李嘉豪",
                            "EMP202606240001", "CREATED", "HR员工已创建")),
                    List.of());
        };
        FakeFeishuBitableClient bitableClient = new FakeFeishuBitableClient();
        bitableClient.tables = List.of(new BitableTable("tblStaff", "渡江战役团队管理"));
        bitableClient.fields = List.of(new FeishuBitableClient.BitableField("fldPhoto", "照片"));
        bitableClient.records = List.of(new BitableRecord("recStaff1", Map.<String, Object>of(
                "销售姓名", "2026-06-24-李嘉豪",
                "fldPhoto", List.of(Map.of(
                        "file_token", "file-token-1",
                        "name", "face.png",
                        "type", "image/png")))));
        bitableClient.downloads.put("file-token-1",
                new DownloadedAttachment("file-token-1", "face.png", "image/png", new byte[]{1, 2, 3}));
        CapturingStorage storage = new CapturingStorage();
        FeishuAttachmentImportService attachmentService = new FeishuAttachmentImportService(
                store, bitableClient, storage,
                new FeishuAttachmentObjectKeyFactory("feishu-attachments"));
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, "DEFAULT"), new AtomicReference<>()),
                hrClient, null, null, null, attachmentService);
        MockMultipartFile file = xlsx("销售人员信息.xlsx", "渡江战役团队管理", List.of(
                List.of("销售姓名", "姓名", "岗位", "职位", "销售区域", "销售leader", "城市", "手机号",
                        "入职日期", "在职状态", "照片"),
                List.of("2026-06-24-李嘉豪", "李嘉豪", "销售", "城市总", "华北地区",
                        "张海龙", "西安", "", "46197", "在职", "face.png")));

        service.preflight(caller(), file,
                "https://mcn7tpu25x8w.feishu.cn/base/appToken123?table=tblStaff&view=vewStaff");
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.uploadedAttachmentCount()).isEqualTo(1);
        assertThat(result.failedAttachmentRows()).isZero();
        assertThat(capturedRows.get()).hasSize(1);
        assertThat(storage.objects).hasSize(1);
        String objectKey = storage.objects.keySet().iterator().next();
        assertThat(objectKey)
                .startsWith(TENANT_ID + "/feishu-attachments/FEISHU_SALES_STAFF/2026-06-24-李嘉豪/")
                .contains("/照片/")
                .endsWith(".png");
        assertThat(store.attachmentUpdates).singleElement().satisfies(update -> {
            assertThat(update.values().get("照片")).isEqualTo(objectKey);
            assertThat(update.attachmentRefs().get("照片")).containsExactly(objectKey);
        });
        assertThat(store.projectionUpdates).singleElement().satisfies(update ->
                assertThat(update.projectionStatus()).isEqualTo("PROJECTED"));
    }

    @Test
    void formalRunMarksProjectedBusinessRowWaitingWhenAttachmentSourceIsMissing() {
        CapturingStore store = new CapturingStore();
        AtomicReference<SalesOrderCommand> capturedOrder = new AtomicReference<>();
        String sourceNo = "DD202609013391";
        CrmCustomerProjectionClient crmClient = new CrmCustomerProjectionClient() {
            @Override
            public ExternalCrmAreaSyncResult syncAreas(CallerIdentity caller, String sourceSystem,
                                                       List<ExternalCrmAreaRowCommand> rows) {
                return new ExternalCrmAreaSyncResult(rows.size(), 0, 0, rows.size(), 0,
                        rows.stream()
                                .map(row -> new ExternalCrmAreaSyncRowResult(
                                        row.sourceAreaId(), "REGION", "CITY", "UNCHANGED", "已存在"))
                                .toList(),
                        List.of());
            }

            @Override
            public ExternalCrmCustomerSyncResult sync(CallerIdentity caller, String sourceSystem,
                                                      List<ExternalCrmCustomerRowCommand> rows) {
                return new ExternalCrmCustomerSyncResult(rows.size(), rows.size(), 0, 0, 0,
                        rows.stream()
                                .map(row -> new ExternalCrmCustomerSyncRowResult(
                                        row.sourceCustomerId(), 1001L, "C202609010001",
                                        "CREATED", "CRM客户已创建"))
                                .toList(),
                        List.of());
            }
        };
        ErpProductProjectionClient erpClient = new ErpProductProjectionClient() {
            @Override
            public ExternalProductSyncResult sync(CallerIdentity caller, String sourceSystem,
                                                  List<ExternalProductRowCommand> rows) {
                return new ExternalProductSyncResult(0, 0, 0, 0, 0, List.of(), List.of());
            }

            @Override
            public List<ExternalProductResolvedView> resolve(CallerIdentity caller, String preferredSourceSystem,
                                                             List<ExternalProductResolveRowCommand> rows) {
                return rows.stream()
                        .map(row -> new ExternalProductResolvedView(row.referenceId(),
                                2001L, 3001L, "PRD202609010001", "SKU202609010001",
                                "油泼辣子拌面", "默认规格", "BOX", "DINGHUOBAO",
                                "PRODUCT_NAME_EXACT", 95, "MATCHED", "已匹配"))
                        .toList();
            }
        };
        FeishuAttachmentImportService attachmentService = new FeishuAttachmentImportService(
                store, null, null, null);
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, sourceNo), capturedOrder),
                null, crmClient, erpClient, null, attachmentService);
        MockMultipartFile file = xlsx("销售订单含附件.xlsx", List.of(
                new SheetSource("销售订单表", List.of(
                        List.of("订单编号", "关联门店", "门店", "城市", "销售", "销售日期", "付款凭证"),
                        List.of(sourceNo, "SP1692 - A8台球俱乐部", "A8台球俱乐部", "上海",
                                "刘鹏昆", "2026-09-01", "pay.png"))),
                new SheetSource("订单明细表", List.of(
                        List.of("订单明细号", "关联订单", "产品编号", "数量(箱)", "实际小计"),
                        List.of("ODD202609013391001", sourceNo,
                                "杨掌柜-油泼辣子拌面-12桶/箱", "3", "¥234.00")))));

        service.preflight(caller(), file, null);
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.projectedRows()).isEqualTo(1);
        assertThat(result.failedRows()).isZero();
        assertThat(result.waitingMappingRows()).isEqualTo(1);
        assertThat(result.failedAttachmentRows()).isEqualTo(1);
        assertThat(capturedOrder.get()).isNotNull();
        assertThat(result.rows()).filteredOn(row -> "SALES_ORDER".equals(row.targetObjectType()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.projectionStatus()).isEqualTo("WAITING_MAPPING");
                    assertThat(row.targetId()).isEqualTo("9001");
                    assertThat(row.message()).contains("业务主体已写入").contains("附件未入库");
                });
    }

    @Test
    void runProjectsSalesStaffInSingleDomainBatchWhenWrite() {
        CapturingStore store = new CapturingStore();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<List<ExternalEmployeeRowCommand>> capturedRows = new AtomicReference<>();
        HrEmployeeProjectionClient hrClient = (caller, sourceSystem, rows) -> {
            calls.incrementAndGet();
            capturedRows.set(rows);
            return new ExternalEmployeeSyncResult(rows.size(), rows.size(), 0, 0, 0,
                    rows.stream()
                            .map(row -> new ExternalEmployeeSyncRowResult(row.sourceEmployeeId(),
                                    "EMP-" + row.sourceEmployeeId(), "CREATED", "HR员工已创建"))
                            .toList(),
                    List.of());
        };
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, "DEFAULT"), new AtomicReference<>()),
                hrClient);
        MockMultipartFile file = xlsx("销售人员信息.xlsx", "渡江战役团队管理", List.of(
                List.of("销售姓名", "姓名", "岗位", "职位", "销售区域", "销售leader", "城市", "手机号",
                        "入职日期", "在职状态"),
                List.of("2026-06-24-李嘉豪", "李嘉豪", "销售", "城市总", "华北地区",
                        "张海龙", "西安", "", "46197", "在职"),
                List.of("2026-07-02-巨润虎", "巨润虎", "销售", "销售员", "华北地区",
                        "李嘉豪", "西安", "", "46205", "在职"),
                List.of("2026-07-06-高文波", "高文波", "销售", "销售员", "华北地区",
                        "李嘉豪", "西安", "", "46209", "在职")));

        service.preflight(caller(), file, null);
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(calls).hasValue(1);
        assertThat(capturedRows.get()).hasSize(3);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.projectedRows()).isEqualTo(3);
        assertThat(result.waitingMappingRows()).isZero();
        assertThat(store.projectionUpdates).hasSize(3);
    }

    @Test
    void runSkipsPlaceholderSalesStaffRowsWithoutPartialStatus() {
        CapturingStore store = new CapturingStore();
        AtomicReference<List<ExternalEmployeeRowCommand>> capturedRows = new AtomicReference<>();
        HrEmployeeProjectionClient hrClient = (caller, sourceSystem, rows) -> {
            capturedRows.set(rows);
            return new ExternalEmployeeSyncResult(rows.size(), rows.size(), 0, 0, 0,
                    rows.stream()
                            .map(row -> new ExternalEmployeeSyncRowResult(row.sourceEmployeeId(),
                                    "EMP-" + row.sourceEmployeeId(), "CREATED", "HR员工已创建"))
                            .toList(),
                    List.of());
        };
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, "DEFAULT"), new AtomicReference<>()),
                hrClient);
        MockMultipartFile file = xlsx("销售人员信息.xlsx", "渡江战役团队管理", List.of(
                List.of("销售姓名", "姓名", "岗位", "职位", "销售区域", "销售leader", "城市", "手机号",
                        "入职日期", "在职状态"),
                List.of("2026-06-24-李嘉豪", "李嘉豪", "销售", "城市总", "华北地区",
                        "张海龙", "西安", "", "46197", "在职"),
                List.of("-", "", "", "", "", "", "", "", "", "")));

        service.preflight(caller(), file, null);
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(capturedRows.get()).hasSize(1);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.projectedRows()).isEqualTo(1);
        assertThat(result.skippedRows()).isEqualTo(1);
        assertThat(result.waitingMappingRows()).isZero();
        assertThat(result.rows()).extracting("projectionStatus")
                .containsExactly("PROJECTED", "SKIPPED");
        assertThat(store.batchStatus).isEqualTo("SUCCEEDED");
    }

    @Test
    void runProjectsCrmCustomerAndStoreWhenWrite() {
        CapturingStore store = new CapturingStore();
        AtomicReference<CallerIdentity> capturedCaller = new AtomicReference<>();
        AtomicReference<String> capturedSourceSystem = new AtomicReference<>();
        AtomicReference<List<ExternalCrmCustomerRowCommand>> capturedRows = new AtomicReference<>();
        CrmCustomerProjectionClient crmClient = new CrmCustomerProjectionClient() {
            @Override
            public ExternalCrmAreaSyncResult syncAreas(CallerIdentity caller, String sourceSystem,
                                                       List<ExternalCrmAreaRowCommand> rows) {
                return new ExternalCrmAreaSyncResult(rows.size(), rows.size(), 0, 0, 0,
                        rows.stream().map(row -> new ExternalCrmAreaSyncRowResult(
                                row.sourceAreaId(), null, "AREA202609010001",
                                "CREATED", "CRM地区已创建")).toList(),
                        List.of());
            }

            @Override
            public ExternalCrmCustomerSyncResult sync(CallerIdentity caller, String sourceSystem,
                                                      List<ExternalCrmCustomerRowCommand> rows) {
                capturedCaller.set(caller);
                capturedSourceSystem.set(sourceSystem);
                capturedRows.set(rows);
                return new ExternalCrmCustomerSyncResult(1, 1, 0, 0, 0,
                        List.of(new ExternalCrmCustomerSyncRowResult("SP1001", 8101L,
                                "C202609010001", "CREATED", "CRM客户已创建")),
                        List.of());
            }
        };
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, "DEFAULT"), new AtomicReference<>()),
                null, crmClient, null, null);
        MockMultipartFile file = xlsx("门店信息库.xlsx", "门店信息库", List.of(
                List.of("客户编号", "客户名称", "城市", "状态", "负责人", "客户类型", "客户地址", "创建时间"),
                List.of("SP1001", "武汉门店", "武汉", "营业中", "张三", "台球",
                        "湖北省武汉市江汉区示例路1号",
                        "2026-09-01 10:00:00")));

        service.preflight(caller(), file, null);
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.projectedRows()).isEqualTo(1);
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.projectionStatus()).isEqualTo("PROJECTED");
            assertThat(row.targetDomain()).isEqualTo("CRM");
            assertThat(row.targetObjectType()).isEqualTo("CUSTOMER");
            assertThat(row.targetId()).isEqualTo("8101");
        });
        assertThat(capturedCaller.get().principalScope()).isEqualTo("SERVICE");
        assertThat(capturedCaller.get().permissions()).contains("crm:customer:sync");
        assertThat(capturedSourceSystem.get()).isEqualTo("FEISHU");
        assertThat(capturedRows.get()).singleElement().satisfies(row -> {
            assertThat(row.sourceTenantKey()).isEqualTo("FEISHU_STORE");
            assertThat(row.sourceCustomerId()).isEqualTo("SP1001");
            assertThat(row.customerName()).isEqualTo("武汉门店");
            assertThat(row.cityName()).isEqualTo("武汉");
            assertThat(row.customerSourceName()).isEqualTo("门店信息库");
            assertThat(row.businessCategoryName()).isEqualTo("台球");
            assertThat(row.ownerEmployeeNameSnapshot()).isEqualTo("张三");
            assertThat(row.address()).isEqualTo("湖北省武汉市江汉区示例路1号");
        });
        assertThat(store.batchStatus).isEqualTo("SUCCEEDED");
    }

    @Test
    void crmAreaPreSyncFailureDoesNotAbortRowProjection() {
        CapturingStore store = new CapturingStore();
        CrmCustomerProjectionClient crmClient = new CrmCustomerProjectionClient() {
            @Override
            public ExternalCrmAreaSyncResult syncAreas(CallerIdentity caller, String sourceSystem,
                                                       List<ExternalCrmAreaRowCommand> rows) {
                return new ExternalCrmAreaSyncResult(rows.size(), 0, 0, 0, rows.size(),
                        List.of(), List.of("CRM地区服务暂时不可用"));
            }

            @Override
            public ExternalCrmCustomerSyncResult sync(CallerIdentity caller, String sourceSystem,
                                                      List<ExternalCrmCustomerRowCommand> rows) {
                return new ExternalCrmCustomerSyncResult(rows.size(), rows.size(), 0, 0, 0,
                        rows.stream().map(row -> new ExternalCrmCustomerSyncRowResult(
                                row.sourceCustomerId(), 8101L, "C202609010001",
                                "CREATED", "CRM客户已创建")).toList(),
                        List.of());
            }
        };
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, "DEFAULT"), new AtomicReference<>()),
                null, crmClient, null, null);
        MockMultipartFile file = xlsx("门店信息库.xlsx", "门店信息库", List.of(
                List.of("门店编码", "门店名称", "城市", "门店状态", "销售", "门店属性", "创建时间"),
                List.of("SP1001", "武汉门店", "武汉", "营业中", "张三", "台球",
                        "2026-09-01 10:00:00")));

        service.preflight(caller(), file, null);
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.projectedRows()).isEqualTo(1);
        assertThat(result.failedRows()).isZero();
        assertThat(store.projectionUpdates).singleElement().satisfies(update -> {
            assertThat(update.projectionStatus()).isEqualTo("PROJECTED");
            assertThat(update.targetDomain()).isEqualTo("CRM");
        });
    }

    @Test
    void runProjectsErpProductWhenWrite() {
        CapturingStore store = new CapturingStore();
        AtomicReference<CallerIdentity> capturedCaller = new AtomicReference<>();
        AtomicReference<String> capturedSourceSystem = new AtomicReference<>();
        AtomicReference<List<ExternalProductRowCommand>> capturedRows = new AtomicReference<>();
        ErpProductProjectionClient erpClient = (caller, sourceSystem, rows) -> {
            capturedCaller.set(caller);
            capturedSourceSystem.set(sourceSystem);
            capturedRows.set(rows);
            return new ExternalProductSyncResult(1, 1, 0, 0, 0,
                    List.of(new ExternalProductSyncRowResult("P1001", 9101L, 9201L,
                            "P202609010001", "SKU202609010001", "BOX",
                            "CREATED", "ERP商品已创建")),
                    List.of());
        };
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, "DEFAULT"), new AtomicReference<>()),
                null, null, erpClient, null);
        MockMultipartFile file = xlsx("产品信息库.xlsx", "产品信息库", List.of(
                List.of("产品编码", "产品名称", "业务线", "品牌", "行业", "品类", "定价", "规格", "状态", "创建时间"),
                List.of("P1001", "酸辣粉", "零售业务", "瑞盖", "食品", "粉面",
                        "18.50", "箱", "在售", "2026-09-01 10:00:00")));

        service.preflight(caller(), file, null);
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.projectedRows()).isEqualTo(1);
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.projectionStatus()).isEqualTo("PROJECTED");
            assertThat(row.targetDomain()).isEqualTo("ERP");
            assertThat(row.targetObjectType()).isEqualTo("PRODUCT");
            assertThat(row.targetId()).isEqualTo("9101");
        });
        assertThat(capturedCaller.get().principalScope()).isEqualTo("SERVICE");
        assertThat(capturedCaller.get().permissions()).contains("erp:product:sync");
        assertThat(capturedSourceSystem.get()).isEqualTo("FEISHU");
        assertThat(capturedRows.get()).singleElement().satisfies(row -> {
            assertThat(row.sourceTenantKey()).isEqualTo("FEISHU_PRODUCT");
            assertThat(row.sourceProductId()).isEqualTo("P1001");
            assertThat(row.productName()).isEqualTo("酸辣粉");
            assertThat(row.businessLineName()).isEqualTo("零售业务");
            assertThat(row.brandName()).isEqualTo("瑞盖");
            assertThat(row.categoryName()).isEqualTo("粉面");
            assertThat(row.salePrice()).isEqualByComparingTo("18.50");
            assertThat(row.unitCode()).isEqualTo("BOX");
        });
        assertThat(store.batchStatus).isEqualTo("SUCCEEDED");
    }

    @Test
    void runKeepsProjectableRowsWaitingWhenSourceCreatedAtMissing() {
        CapturingStore store = new CapturingStore();
        ErpProductProjectionClient erpClient = (caller, sourceSystem, rows) -> {
            throw new AssertionError("missing sourceCreatedAt rows must not be projected");
        };
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, "DEFAULT"), new AtomicReference<>()),
                null, null, erpClient, null);
        MockMultipartFile file = xlsx("产品信息库.xlsx", "产品信息库", List.of(
                List.of("产品编码", "产品名称", "业务线", "品牌", "行业", "品类", "定价", "规格", "状态"),
                List.of("P1001", "酸辣粉", "零售业务", "瑞盖", "食品", "粉面",
                        "18.50", "箱", "在售")));

        service.preflight(caller(), file, null);
        FeishuImportRunResult result = service.run(caller(), store.saved.id(),
                new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.projectedRows()).isZero();
        assertThat(result.waitingMappingRows()).isEqualTo(1);
        assertThat(store.projectionUpdates).singleElement().satisfies(update -> {
            assertThat(update.projectionStatus()).isEqualTo("WAITING_MAPPING");
            assertThat(update.errorCode()).isEqualTo("FEISHU_SOURCE_CREATED_AT_REQUIRED");
        });
    }

    @Test
    void runSyncsObservedBusinessDictionariesBeforeProjection() {
        CapturingStore store = new CapturingStore();
        BusinessDictionaryBatchClient dictionaryClient = mock(BusinessDictionaryBatchClient.class);
        when(dictionaryClient.sync(any(), eq("FEISHU_IMPORT"), any()))
                .thenReturn(BusinessDictionaryBatchClient.Audit.empty());
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, "DEFAULT"), new AtomicReference<>()),
                null, null, null, dictionaryClient);
        MockMultipartFile file = xlsx("门店信息库.xlsx", "门店信息库", List.of(
                List.of("门店编码", "门店名称", "城市", "门店状态", "门店属性", "标签", "创建时间"),
                List.of("SP1001", "武汉门店", "武汉", "营业中", "台球", "单店, 可动销",
                        "2026-09-01 10:00:00")));

        service.preflight(caller(), file, null);
        service.run(caller(), store.saved.id(), new FeishuImportRunCommand(10, false));

        ArgumentCaptor<List<Observation>> observations = ArgumentCaptor.forClass(List.class);
        verify(dictionaryClient).sync(any(), eq("FEISHU_IMPORT"), observations.capture());
        assertThat(observations.getValue()).extracting(Observation::dictionaryCode)
                .contains("STORE_STATUS", "STORE_ATTRIBUTE", "STORE_TAG");
        assertThat(observations.getValue()).extracting(Observation::dictionaryCode)
                .doesNotContain("CITY", "REGION", "HR_POSITION", "HR_JOB_CATEGORY");
        assertThat(observations.getValue()).anySatisfy(item -> {
            assertThat(item.dictionaryCode()).isEqualTo("STORE_TAG");
            assertThat(item.sourceValue()).isEqualTo("可动销");
        });
    }

    @Test
    void dictionaryMapperSeparatesSameNamedFieldsBySheetSemantics() {
        FeishuDictionaryObservationMapper mapper = new FeishuDictionaryObservationMapper();

        List<Observation> observations = mapper.observations(List.of(
                storedRow("FEISHU_SAMPLE_REQUEST", "ORDER", "SAMPLE_REQUEST", Map.of(
                        "城市", "武汉", "类型", "补寄", "状态", "待处理")),
                storedRow("FEISHU_FELT_PICKUP_ORDER", "ORDER", "FELT_PICKUP_ORDER", Map.of(
                        "所属城市", "武汉", "付款状态", "已付款")),
                storedRow("FEISHU_QUALITY_FEEDBACK", "ERP", "QUALITY_FEEDBACK", Map.of(
                        "问题类型", "破损", "严重程度", "高", "发现渠道", "门店反馈",
                        "城市处理状态", "处理中", "厂家赔付状态", "未赔付")),
                storedRow("FEISHU_STORE", "CRM", "STORE", Map.of(
                        "城市", "西安", "营业状态", "营业中", "属性", "台球",
                        "经营类型", "竞技赛事, 综合经营", "合作意向", "高意向",
                        "门店标签", "单店, 可动销")),
                storedRow("FEISHU_SALES_STAFF", "HR", "EMPLOYEE", Map.of(
                        "岗位", "销售", "职位", "城市总", "在职状态", "在职",
                        "销售区域", "华北地区", "城市", "西安"))));

        assertThat(observations).anySatisfy(item -> {
            assertThat(item.dictionaryCode()).isEqualTo("SAMPLE_REQUEST_STATUS");
            assertThat(item.sourceValue()).isEqualTo("待处理");
        }).anySatisfy(item -> {
            assertThat(item.dictionaryCode()).isEqualTo("FELT_PICKUP_PAYMENT_STATUS");
            assertThat(item.sourceValue()).isEqualTo("已付款");
        }).anySatisfy(item -> {
            assertThat(item.dictionaryCode()).isEqualTo("QUALITY_ISSUE_TYPE");
            assertThat(item.sourceValue()).isEqualTo("破损");
        }).anySatisfy(item -> {
            assertThat(item.dictionaryCode()).isEqualTo("STORE_BUSINESS_TYPE");
            assertThat(item.sourceValue()).isEqualTo("综合经营");
        }).anySatisfy(item -> {
            assertThat(item.dictionaryCode()).isEqualTo("EMPLOYEE_STATUS");
            assertThat(item.sourceValue()).isEqualTo("在职");
        });
        assertThat(observations).extracting(Observation::dictionaryCode)
                .doesNotContain("CITY", "REGION", "HR_POSITION", "HR_JOB_CATEGORY");
    }

    @Test
    void runProjectsExistingHrEmployeeBatchEvenWhenTableCodeDiffers() {
        CapturingStore store = new CapturingStore();
        UUID batchId = UUID.randomUUID();
        UUID tableId = UUID.randomUUID();
        UUID rawRowId = UUID.randomUUID();
        store.saved = new PreflightBatch(batchId, TENANT_ID, USER_ID, "FEISHU",
                null, "legacy-staff.xlsx", 128L, "hash", "PREFLIGHTED",
                1, 1, 0, NOW,
                List.of(new FeishuImportStore.PreflightTable(tableId, "渡江战役团队管理",
                        "LEGACY_SALES_STAFF", "HR", "EMPLOYEE", "READY",
                        1, 1, 11, 0,
                        List.of("销售姓名", "姓名", "岗位", "职位", "销售区域", "销售leader",
                                "城市", "手机号", "入职日期", "在职状态"),
                        List.of())),
                List.of(),
                List.of(new FeishuImportStore.PreflightRawRow(rawRowId, tableId, "渡江战役团队管理",
                        "LEGACY_SALES_STAFF", "HR", "EMPLOYEE", 2,
                        "2026-06-24-李嘉豪", NOW, "row-hash",
                        Map.of("销售姓名", "2026-06-24-李嘉豪",
                                "姓名", "李嘉豪",
                                "岗位", "销售",
                                "职位", "城市总",
                                "销售区域", "华北地区",
                                "销售leader", "张海龙",
                                "城市", "西安",
                                "入职日期", "46197",
                                "在职状态", "在职"),
                        Map.of())));
        HrEmployeeProjectionClient hrClient = (caller, sourceSystem, rows) ->
                new ExternalEmployeeSyncResult(1, 1, 0, 0, 0,
                        List.of(new ExternalEmployeeSyncRowResult("2026-06-24-李嘉豪",
                                "EMP202606240001", "CREATED", "HR员工已创建")),
                        List.of());
        FeishuImportBundleService service = service(store,
                orderProjectionClient(Optional.empty(), salesOrderDetail(9001L, "DEFAULT"), new AtomicReference<>()),
                hrClient);

        FeishuImportRunResult result = service.run(caller(), batchId, new FeishuImportRunCommand(10, false));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.projectedRows()).isEqualTo(1);
        assertThat(result.waitingMappingRows()).isZero();
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.projectionStatus()).isEqualTo("PROJECTED");
            assertThat(row.targetDomain()).isEqualTo("HR");
            assertThat(row.targetObjectType()).isEqualTo("EMPLOYEE");
            assertThat(row.targetId()).isEqualTo("EMP202606240001");
        });
        assertThat(store.projectionUpdates).hasSize(1);
    }

    @Test
    void preflightKeepsSalesOrderSheetRunnableWhenExactOrderNoHeaderIsMissing() {
        FeishuImportBundleService service = service(new CapturingStore());
        MockMultipartFile file = xlsx("销售订单.xlsx", "销售订单表", List.of(
                List.of("门店", "创建时间"),
                List.of("武汉门店", "2026-09-01 10:00:00")));

        FeishuImportPreflightResult result = service.preflight(caller(), file, null);

        assertThat(result.status()).isEqualTo("PREFLIGHTED_WITH_WARNINGS");
        assertThat(result.tables()).singleElement().satisfies(table -> {
            assertThat(table.tableCode()).isEqualTo("FEISHU_SALES_ORDER");
            assertThat(table.mappingStatus()).isEqualTo("READY");
        });
        assertThat(result.issues()).anySatisfy(issue -> {
            assertThat(issue.severity()).isEqualTo("WARN");
            assertThat(issue.issueType()).isEqualTo("FEISHU_SOURCE_DOCUMENT_NO_MISSING");
            assertThat(issue.fieldName()).isEqualTo("来源单号");
        });
    }

    @Test
    void preflightOnlyAcceptsXlsxFile() {
        FeishuImportBundleService service = service(new CapturingStore());
        MockMultipartFile file = new MockMultipartFile("file", "orders.csv",
                "text/csv", "订单编号\n1".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.preflight(caller(), file, null))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST))
                .hasMessageContaining("xlsx");
    }

    private static FeishuImportBundleService service(CapturingStore store) {
        return service(store, orderProjectionClient(Optional.empty(),
                salesOrderDetail(9001L, "DEFAULT"), new AtomicReference<>()));
    }

    private static FeishuImportBundleService service(CapturingStore store,
                                                     OrderSalesOrderProjectionClient projectionClient) {
        return service(store, projectionClient, null);
    }

    private static FeishuImportBundleService service(CapturingStore store,
                                                     OrderSalesOrderProjectionClient projectionClient,
                                                     HrEmployeeProjectionClient hrClient) {
        return service(store, projectionClient, hrClient, null, null, null);
    }

    private static FeishuImportBundleService service(CapturingStore store,
                                                     OrderSalesOrderProjectionClient projectionClient,
                                                     HrEmployeeProjectionClient hrClient,
                                                     CrmCustomerProjectionClient crmClient,
                                                     ErpProductProjectionClient erpClient,
                                                     BusinessDictionaryBatchClient dictionaryClient) {
        return service(store, projectionClient, hrClient, crmClient, erpClient, dictionaryClient, null);
    }

    private static FeishuImportBundleService service(CapturingStore store,
                                                     OrderSalesOrderProjectionClient projectionClient,
                                                     HrEmployeeProjectionClient hrClient,
                                                     CrmCustomerProjectionClient crmClient,
                                                     ErpProductProjectionClient erpClient,
                                                     BusinessDictionaryBatchClient dictionaryClient,
                                                     FeishuAttachmentImportService attachmentImportService) {
        FeishuImportProperties properties = new FeishuImportProperties();
        properties.setSampleRows(3);
        return new FeishuImportBundleService(store, projectionClient, hrClient, crmClient, erpClient,
                dictionaryClient, attachmentImportService, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static OrderSalesOrderProjectionClient orderProjectionClient(
            Optional<SalesOrderDetailView> existing,
            SalesOrderDetailView created,
            AtomicReference<SalesOrderCommand> captured) {
        return orderProjectionClient(existing, created, captured, Optional.empty(), new AtomicReference<>());
    }

    private static OrderSalesOrderProjectionClient orderProjectionClient(
            Optional<SalesOrderDetailView> existing,
            SalesOrderDetailView created,
            AtomicReference<SalesOrderCommand> captured,
            Optional<SalesPaymentRecordDetailView> existingPayment,
            AtomicReference<SalesPaymentRecordCommand> capturedPayment) {
        return (OrderSalesOrderProjectionClient) Proxy.newProxyInstance(
                OrderSalesOrderProjectionClient.class.getClassLoader(),
                new Class<?>[]{OrderSalesOrderProjectionClient.class},
                (proxy, method, args) -> {
                    if ("findSalesOrderBySource".equals(method.getName())) return existing;
                    if ("createSalesOrder".equals(method.getName())) {
                        captured.set((SalesOrderCommand) args[1]);
                        return created;
                    }
                    if ("updateSalesOrder".equals(method.getName())) {
                        captured.set((SalesOrderCommand) args[2]);
                        return created;
                    }
                    if ("findSalesPaymentBySource".equals(method.getName())) return existingPayment;
                    if ("createSalesPayment".equals(method.getName())) {
                        SalesPaymentRecordCommand command = (SalesPaymentRecordCommand) args[1];
                        capturedPayment.set(command);
                        return salesPaymentDetail(9101L, "PAY202609020001", command, 1);
                    }
                    if ("updateSalesPayment".equals(method.getName())) {
                        SalesPaymentRecordCommand command = (SalesPaymentRecordCommand) args[2];
                        capturedPayment.set(command);
                        return salesPaymentDetail((Long) args[1], "PAY202609020001", command,
                                command.revision() == null ? 1 : command.revision() + 1);
                    }
                    if ("toString".equals(method.getName())) return "FeishuImportBundleServiceTestOrderProjectionClient";
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static OrderSalesOrderProjectionClient orderProjectionClientCapturingLists(
            Optional<SalesOrderDetailView> existing,
            SalesOrderDetailView created,
            List<SalesOrderCommand> captured,
            Optional<SalesPaymentRecordDetailView> existingPayment,
            List<SalesPaymentRecordCommand> capturedPayment) {
        return (OrderSalesOrderProjectionClient) Proxy.newProxyInstance(
                OrderSalesOrderProjectionClient.class.getClassLoader(),
                new Class<?>[]{OrderSalesOrderProjectionClient.class},
                (proxy, method, args) -> {
                    if ("findSalesOrderBySource".equals(method.getName())) return existing;
                    if ("createSalesOrder".equals(method.getName())) {
                        captured.add((SalesOrderCommand) args[1]);
                        return created;
                    }
                    if ("updateSalesOrder".equals(method.getName())) {
                        captured.add((SalesOrderCommand) args[2]);
                        return created;
                    }
                    if ("findSalesPaymentBySource".equals(method.getName())) return existingPayment;
                    if ("createSalesPayment".equals(method.getName())) {
                        SalesPaymentRecordCommand command = (SalesPaymentRecordCommand) args[1];
                        capturedPayment.add(command);
                        return salesPaymentDetail(9101L, "PAY202609020001", command, 1);
                    }
                    if ("updateSalesPayment".equals(method.getName())) {
                        SalesPaymentRecordCommand command = (SalesPaymentRecordCommand) args[2];
                        capturedPayment.add(command);
                        return salesPaymentDetail((Long) args[1], "PAY202609020001", command,
                                command.revision() == null ? 1 : command.revision() + 1);
                    }
                    if ("toString".equals(method.getName())) return "FeishuImportBundleServiceTestOrderProjectionClient";
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static SalesOrderDetailView salesOrderDetail(Long id, String sourceNo) {
        return salesOrderDetail(id, sourceNo, null, null);
    }

    private static SalesOrderDetailView salesOrderDetail(Long id, String sourceNo,
                                                        String ownerEmployeeCode,
                                                        String ownerEmployeeName) {
        return new SalesOrderDetailView(id, "SO202609010001", "FEISHU", sourceNo,
                1001L, "C1001", "武汉门店", null, null, null,
                null, ownerEmployeeName == null ? "张三" : ownerEmployeeName,
                ownerEmployeeCode, ownerEmployeeName, NOW, null, null,
                "DRAFT", null, null, "UNPAID", null,
                BigDecimal.ONE, BigDecimal.TEN, null, BigDecimal.ZERO,
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, null, 1,
                "test", NOW, "test", NOW, List.of());
    }

    private static SalesOrderDetailView salesOrderDetail(Long id, String sourceNo,
                                                        BigDecimal totalQuantity,
                                                        BigDecimal originalAmount,
                                                        BigDecimal discountAmount,
                                                        BigDecimal payableAmount,
                                                        List<SalesOrderLineView> lines) {
        return new SalesOrderDetailView(id, "SO202609010001", "FEISHU", sourceNo,
                null, null, null, null,
                "NEEDS_REVIEW", "待补齐：商品明细",
                1001L, "C1001", "武汉门店", null, null, null,
                null, "张三", null, null, NOW, null, null, null,
                "DRAFT", null, null, "UNPAID", null,
                totalQuantity, originalAmount, null, discountAmount, payableAmount,
                BigDecimal.ZERO, payableAmount, null, 1,
                "test", NOW, "test", NOW, lines);
    }

    private static SalesOrderLineView salesOrderLine(Long id, Long productId, Long productVariantId,
                                                     String productName, BigDecimal quantity,
                                                     BigDecimal unitPrice, BigDecimal discountAmount) {
        BigDecimal originalAmount = quantity.multiply(unitPrice);
        return new SalesOrderLineView(id, 1, productId, productVariantId,
                "PRD202609010001", "SKU202609010001", productName,
                "默认规格", "BOX", quantity, unitPrice, null, discountAmount,
                originalAmount.subtract(discountAmount), null);
    }

    private static SalesPaymentRecordDetailView salesPaymentDetail(
            Long id, String paymentNo, SalesPaymentRecordCommand command, int revision) {
        return new SalesPaymentRecordDetailView(id, paymentNo, command.connectorId(),
                command.sourceSystemCode(), command.sourceDocumentNo(), command.orderId(),
                "SO202609010001", 1001L, "C1001", "武汉门店",
                command.collectorStaffCode(), command.collectorNameSnapshot(),
                command.paymentTime(), command.paymentMethodCode(), command.paidAmount(),
                command.voucherKeys(), command.remark(), revision,
                "test", NOW, "test", NOW);
    }

    private static CallerIdentity caller() {
        return new CallerIdentity("TENANT", USER_ID, TENANT_ID, USER_ID, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("TENANT_ADMIN"),
                Set.of("integration:feishu:import"));
    }

    private static MockMultipartFile xlsx(String fileName, String sheetName, List<List<String>> rows) {
        return xlsx(fileName, List.of(new SheetSource(sheetName, rows)));
    }

    private static MockMultipartFile xlsx(String fileName, List<SheetSource> sheets) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
                entry(zip, "[Content_Types].xml", """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                          <Default Extension="xml" ContentType="application/xml"/>
                          <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                          %s
                        </Types>
                        """.formatted(sheetContentTypes(sheets)));
                entry(zip, "xl/workbook.xml", """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                                  xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                          <sheets>%s</sheets>
                        </workbook>
                        """.formatted(workbookSheets(sheets)));
                entry(zip, "xl/_rels/workbook.xml.rels", """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                          %s
                        </Relationships>
                        """.formatted(workbookRelationships(sheets)));
                for (int index = 0; index < sheets.size(); index++) {
                    entry(zip, "xl/worksheets/sheet" + (index + 1) + ".xml", sheet(sheets.get(index).rows()));
                }
            }
            return new MockMultipartFile("file", fileName,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    out.toByteArray());
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String sheetContentTypes(List<SheetSource> sheets) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < sheets.size(); index++) {
            builder.append("<Override PartName=\"/xl/worksheets/sheet")
                    .append(index + 1)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        return builder.toString();
    }

    private static String workbookSheets(List<SheetSource> sheets) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < sheets.size(); index++) {
            builder.append("<sheet name=\"")
                    .append(escape(sheets.get(index).name()))
                    .append("\" sheetId=\"")
                    .append(index + 1)
                    .append("\" r:id=\"rId")
                    .append(index + 1)
                    .append("\"/>");
        }
        return builder.toString();
    }

    private static String workbookRelationships(List<SheetSource> sheets) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < sheets.size(); index++) {
            builder.append("<Relationship Id=\"rId")
                    .append(index + 1)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                    .append(index + 1)
                    .append(".xml\"/>");
        }
        return builder.toString();
    }

    private static String sheet(List<List<String>> rows) {
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                """);
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            xml.append("<row r=\"").append(rowIndex + 1).append("\">");
            List<String> row = rows.get(rowIndex);
            for (int columnIndex = 0; columnIndex < row.size(); columnIndex++) {
                xml.append("<c r=\"").append(column(columnIndex + 1)).append(rowIndex + 1)
                        .append("\" t=\"inlineStr\"><is><t>")
                        .append(escape(row.get(columnIndex)))
                        .append("</t></is></c>");
            }
            xml.append("</row>");
        }
        xml.append("</sheetData></worksheet>");
        return xml.toString();
    }

    private static String column(int index) {
        StringBuilder value = new StringBuilder();
        int current = index;
        while (current > 0) {
            current--;
            value.insert(0, (char) ('A' + current % 26));
            current /= 26;
        }
        return value.toString();
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws java.io.IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.stripLeading().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static StoredRawRow storedRow(String tableCode, String domainCode, String objectType,
                                          Map<String, String> values) {
        return new StoredRawRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                TENANT_ID, "sheet", tableCode, domainCode, objectType, 2,
                values.values().stream().findFirst().orElse("row"), NOW, "hash",
                values, Map.of());
    }

    private record SheetSource(String name, List<List<String>> rows) {
    }

    private static final class CapturingStore implements FeishuImportStore {
        private PreflightBatch saved;
        private final List<RowProjectionUpdate> projectionUpdates = new ArrayList<>();
        private final List<RowAttachmentUpdate> attachmentUpdates = new ArrayList<>();
        private final Map<String, ExistingDeduplicationRow> existingDeduplicationRows = new LinkedHashMap<>();
        private String batchStatus;

        @Override
        public void savePreflight(PreflightBatch batch) {
            this.saved = batch;
        }

        @Override
        public Optional<StoredBatch> batch(UUID tenantId, UUID batchId) {
            if (saved == null || !saved.tenantId().equals(tenantId) || !saved.id().equals(batchId)) {
                return Optional.empty();
            }
            return Optional.of(storedBatch());
        }

        @Override
        public List<StoredBatch> recentBatches(UUID tenantId, int limit) {
            if (saved == null || !saved.tenantId().equals(tenantId)) {
                return List.of();
            }
            return List.of(storedBatch());
        }

        @Override
        public List<StoredRawRow> rawRowsForRun(UUID tenantId, UUID batchId, int limit, boolean replayProjected) {
            if (saved == null || !saved.tenantId().equals(tenantId) || !saved.id().equals(batchId)) {
                return List.of();
            }
            return saved.rawRows().stream()
                    .filter(row -> "IMPORTED".equals(row.importStatus()))
                    .filter(row -> replayProjected || !"PROJECTED".equals(row.projectionStatus()))
                    .limit(limit)
                    .map(row -> new StoredRawRow(row.id(), saved.id(), row.tableId(), saved.tenantId(),
                            row.sheetName(), row.tableCode(), row.domainCode(), row.objectType(),
                            row.rowNumber(), row.sourceDocumentNo(), row.sourceCreatedAt(),
                            row.projectionStatus(), row.values(), row.attachmentRefs(),
                            row.deduplicationKey(), row.duplicateScope(), row.duplicateOfRawRowId()))
                    .toList();
        }

        @Override
        public List<StoredRawRow> rawRowsForBatch(UUID tenantId, UUID batchId, int limit) {
            if (saved == null || !saved.tenantId().equals(tenantId) || !saved.id().equals(batchId)) {
                return List.of();
            }
            return saved.rawRows().stream()
                    .limit(limit)
                    .map(row -> new StoredRawRow(row.id(), saved.id(), row.tableId(), saved.tenantId(),
                            row.sheetName(), row.tableCode(), row.domainCode(), row.objectType(),
                            row.rowNumber(), row.sourceDocumentNo(), row.sourceCreatedAt(),
                            row.projectionStatus(), row.values(), row.attachmentRefs(),
                            row.deduplicationKey(), row.duplicateScope(), row.duplicateOfRawRowId()))
                    .toList();
        }

        @Override
        public Map<String, ExistingDeduplicationRow> existingDeduplicationRows(UUID tenantId, Iterable<String> keys) {
            Map<String, ExistingDeduplicationRow> result = new java.util.LinkedHashMap<>();
            for (String key : keys) {
                ExistingDeduplicationRow row = existingDeduplicationRows.get(key);
                if (row != null) result.put(key, row);
            }
            return result;
        }

        @Override
        public void updateRawRowProjection(RowProjectionUpdate update) {
            projectionUpdates.add(update);
        }

        @Override
        public void updateRawRowAttachments(RowAttachmentUpdate update) {
            attachmentUpdates.add(update);
        }

        @Override
        public void updateBatchStatus(UUID tenantId, UUID batchId, String status, UUID updatedBy,
                                      Instant updatedAt) {
            this.batchStatus = status;
        }

        private StoredBatch storedBatch() {
            return new StoredBatch(saved.id(), saved.tenantId(), saved.sourceSystem(),
                    saved.status(), saved.originalFileName(),
                    saved.fileSha256(), saved.sourceUrl(), saved.fileSizeBytes(),
                    saved.totalSheets(), saved.totalRows(), saved.duplicateRows(),
                    saved.attachmentReferenceCount(), saved.createdAt(), saved.createdAt());
        }
    }

    private static final class FakeFeishuBitableClient implements FeishuBitableClient {
        private List<BitableTable> tables = List.of();
        private List<BitableField> fields = List.of();
        private List<BitableRecord> records = List.of();
        private final Map<String, DownloadedAttachment> downloads = new LinkedHashMap<>();

        @Override
        public List<BitableTable> tables(String appToken) {
            return tables;
        }

        @Override
        public List<BitableField> fields(String appToken, String tableId) {
            return fields;
        }

        @Override
        public List<BitableRecord> records(String appToken, String tableId, String viewId) {
            return records;
        }

        @Override
        public DownloadedAttachment downloadAttachment(String fileToken, String fallbackFileName,
                                                       String tableId, String recordId, String fieldId) {
            DownloadedAttachment attachment = downloads.get(fileToken);
            if (attachment == null) {
                throw new IllegalStateException("missing test attachment " + fileToken);
            }
            return attachment;
        }
    }

    private static final class CapturingStorage implements ProductMediaStorage {
        private final Map<String, byte[]> objects = new LinkedHashMap<>();
        private final Set<String> failObjectKeyFragments = new java.util.LinkedHashSet<>();

        @Override
        public boolean exists(String tenantId, String objectKey) {
            return objects.containsKey(objectKey);
        }

        @Override
        public void put(String tenantId, String objectKey, String originalName,
                        String contentType, byte[] content) {
            for (String fragment : failObjectKeyFragments) {
                if (objectKey.contains(fragment)) {
                    throw new IllegalStateException("storage unavailable for " + fragment);
                }
            }
            objects.put(objectKey, content == null ? new byte[0] : content.clone());
        }
    }
}
