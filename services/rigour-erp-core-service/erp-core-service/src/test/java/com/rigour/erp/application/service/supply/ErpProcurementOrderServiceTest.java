package com.rigour.erp.application.service.supply;

import com.rigour.erp.api.v1.model.InternalProcurementOrderCommand;
import com.rigour.erp.api.v1.model.InternalProcurementOrderDetailView;
import com.rigour.erp.api.v1.model.InternalProcurementOrderLineCommand;
import com.rigour.erp.api.v1.model.InternalProcurementOrderLineView;
import com.rigour.erp.api.v1.model.InternalProcurementOrderSummaryView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.port.out.ErpProcurementOrderStore;
import com.rigour.erp.application.port.out.ErpProcurementOrderStore.ProcurementOrderSearchCriteria;
import com.rigour.erp.application.port.out.ErpProcurementOrderStore.ProcurementOrderWrite;
import com.rigour.erp.application.port.out.ErpProcurementOrderStore.ProductVariantSnapshot;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TestAuthorizationContext;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ErpProcurementOrderServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb700-3000-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("019fb700-3000-7000-8000-000000000002");
    private static final String TENANT = TENANT_ID.toString();
    private static final String ACTOR = USER_ID.toString();
    private static final Instant ETA = Instant.parse("2026-08-28T04:00:00Z");

    @AfterEach
    void clearContext() {
        TestAuthorizationContext.clear();
    }

    @Test
    void createGeneratesProcurementNoUsesProductSnapshotAndTotals() {
        ErpProcurementOrderStore store = mock(ErpProcurementOrderStore.class);
        ErpProcurementOrderService service = new ErpProcurementOrderService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:supply:write"));
        when(store.supplierActive(TENANT, 1L)).thenReturn(true);
        when(store.warehouseActive(TENANT, 2L)).thenReturn(true);
        when(store.productVariant(TENANT, 10L, 11L)).thenReturn(Optional.of(
                snapshot(10L, 11L, "PRD-1", "SKU-1", "咖啡豆", "BOX", "8.50")));
        when(store.productVariant(TENANT, 20L, 21L)).thenReturn(Optional.of(
                snapshot(20L, 21L, "PRD-2", "SKU-2", "牛奶", "BUCKET", "4.00")));
        when(store.existsByNo(TENANT, "PO202608201234")).thenReturn(false);
        when(store.create(eq(TENANT), eq("PO202608201234"), any(), eq(ACTOR)))
                .thenReturn(detail(1L, "PO202608201234", "SUBMITTED", 1));

        service.create(new InternalProcurementOrderCommand(true, 1L, 2L, ETA,
                List.of(new InternalProcurementOrderLineCommand(10L, 11L, new BigDecimal("2"), null, " 第一行 "),
                        new InternalProcurementOrderLineCommand(20L, 21L, new BigDecimal("3"),
                                new BigDecimal("3.00"), null)),
                " 采购备注 ", null));

        ArgumentCaptor<ProcurementOrderWrite> command = ArgumentCaptor.forClass(ProcurementOrderWrite.class);
        verify(store).create(eq(TENANT), eq("PO202608201234"), command.capture(), eq(ACTOR));
        ProcurementOrderWrite write = command.getValue();
        assertThat(write.supplierId()).isEqualTo(1L);
        assertThat(write.targetWarehouseId()).isEqualTo(2L);
        assertThat(write.statusCode()).isEqualTo("SUBMITTED");
        assertThat(write.expectedArrivalTime()).isEqualTo(ETA);
        assertThat(write.totalQuantity()).isEqualByComparingTo("5");
        assertThat(write.totalAmount()).isEqualByComparingTo("26.00");
        assertThat(write.remark()).isEqualTo("采购备注");
        assertThat(write.revision()).isZero();
        assertThat(write.lines()).hasSize(2);
        assertThat(write.lines().get(0).lineNo()).isEqualTo(1);
        assertThat(write.lines().get(0).productCode()).isEqualTo("PRD-1");
        assertThat(write.lines().get(0).unitCode()).isEqualTo("BOX");
        assertThat(write.lines().get(0).unitPrice()).isEqualByComparingTo("8.50");
        assertThat(write.lines().get(0).lineAmount()).isEqualByComparingTo("17.00");
        assertThat(write.lines().get(1).unitPrice()).isEqualByComparingTo("3.00");
    }

    @Test
    void listUsesIndependentFiltersWithoutKeywordAggregation() {
        ErpProcurementOrderStore store = mock(ErpProcurementOrderStore.class);
        ErpProcurementOrderService service = new ErpProcurementOrderService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:supply:read"));
        when(store.procurementOrders(eq(TENANT), eq(0), eq(20), any()))
                .thenReturn(new MasterDataPageView<InternalProcurementOrderSummaryView>(0, 0, 20, List.of()));
        Instant from = Instant.parse("2026-08-20T00:00:00Z");
        Instant to = Instant.parse("2026-08-31T00:00:00Z");

        service.procurementOrders(0, 20, " po ", 1L, 2L, " submitted ", from, to);

        ArgumentCaptor<ProcurementOrderSearchCriteria> criteria =
                ArgumentCaptor.forClass(ProcurementOrderSearchCriteria.class);
        verify(store).procurementOrders(eq(TENANT), eq(0), eq(20), criteria.capture());
        assertThat(criteria.getValue().procurementNo()).isEqualTo("po");
        assertThat(criteria.getValue().supplierId()).isEqualTo(1L);
        assertThat(criteria.getValue().targetWarehouseId()).isEqualTo(2L);
        assertThat(criteria.getValue().statusCode()).isEqualTo("SUBMITTED");
        assertThat(criteria.getValue().expectedArrivalFrom()).isEqualTo(from);
        assertThat(criteria.getValue().expectedArrivalTo()).isEqualTo(to);
    }

    @Test
    void createRejectsDuplicateProductVariantLines() {
        ErpProcurementOrderStore store = mock(ErpProcurementOrderStore.class);
        ErpProcurementOrderService service = new ErpProcurementOrderService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:supply:write"));
        when(store.supplierActive(TENANT, 1L)).thenReturn(true);
        when(store.warehouseActive(TENANT, 2L)).thenReturn(true);
        when(store.productVariant(TENANT, 10L, 11L)).thenReturn(Optional.of(
                snapshot(10L, 11L, "PRD-1", "SKU-1", "咖啡豆", "BOX", "8.50")));

        InternalProcurementOrderCommand command = new InternalProcurementOrderCommand(false, 1L, 2L, null,
                List.of(new InternalProcurementOrderLineCommand(10L, 11L, new BigDecimal("1"), null, null),
                        new InternalProcurementOrderLineCommand(10L, 11L, new BigDecimal("2"), null, null)),
                null, null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BAD_REQUEST);
        verify(store, never()).create(eq(TENANT), any(), any(), eq(ACTOR));
    }

    @Test
    void updateRequiresOptimisticRevision() {
        ErpProcurementOrderStore store = mock(ErpProcurementOrderStore.class);
        ErpProcurementOrderService service = new ErpProcurementOrderService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:supply:write"));

        assertThatThrownBy(() -> service.update(1L, new InternalProcurementOrderCommand(
                false, 1L, 2L, null, List.of(), null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BAD_REQUEST);
        verify(store, never()).update(eq(TENANT), eq(1L), any(), eq(ACTOR));
    }

    @Test
    void deleteUsesLogicDeleteStoreWithOptimisticRevision() {
        ErpProcurementOrderStore store = mock(ErpProcurementOrderStore.class);
        ErpProcurementOrderService service = new ErpProcurementOrderService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:supply:write"));

        service.delete(7L, 2);

        verify(store).delete(TENANT, 7L, 2, ACTOR);
    }

    private static ProductVariantSnapshot snapshot(Long productId, Long variantId, String productCode,
                                                   String variantCode, String productName, String unitCode,
                                                   String purchasePrice) {
        return new ProductVariantSnapshot(productId, variantId, productCode, variantCode, productName,
                unitCode, new BigDecimal(purchasePrice));
    }

    private static InternalProcurementOrderDetailView detail(
            Long id, String procurementNo, String statusCode, int revision) {
        return new InternalProcurementOrderDetailView(id, procurementNo, null, null, 1L, "供应商一",
                2L, "默认仓", statusCode, ETA, BigDecimal.ONE, BigDecimal.TEN,
                List.of(new InternalProcurementOrderLineView(1L, 1, 10L, 11L, "PRD-1", "SKU-1",
                        "咖啡豆", "BOX", BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN,
                        BigDecimal.ZERO, null)),
                null, revision, ACTOR, Instant.now(), ACTOR, Instant.now());
    }

    private static BusinessCodeGenerator fixedGenerator() {
        return new BusinessCodeGenerator(
                Clock.fixed(Instant.parse("2026-08-20T03:00:00Z"), ZoneId.of("Asia/Shanghai")),
                ignored -> "1234");
    }

    private static CallerIdentity caller(String permission) {
        return new CallerIdentity("TENANT", USER_ID, TENANT_ID, USER_ID, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("erp"), Set.of(permission));
    }
}
