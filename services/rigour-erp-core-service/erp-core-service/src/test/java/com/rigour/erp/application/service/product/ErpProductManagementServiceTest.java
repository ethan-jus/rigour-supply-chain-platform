package com.rigour.erp.application.service.product;

import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.api.v1.model.ProductImageCommand;
import com.rigour.erp.api.v1.model.ProductManagementCommand;
import com.rigour.erp.api.v1.model.ProductManagementDetailView;
import com.rigour.erp.api.v1.model.ProductManagementSummaryView;
import com.rigour.erp.api.v1.model.ProductVariantCommand;
import com.rigour.erp.application.port.out.ErpProductManagementStore;
import com.rigour.erp.application.port.out.ErpProductManagementStore.ProductSearchCriteria;
import com.rigour.erp.application.port.out.ErpProductManagementStore.ProductWrite;
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

class ErpProductManagementServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb700-1000-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("019fb700-1000-7000-8000-000000000002");
    private static final String TENANT = TENANT_ID.toString();
    private static final String ACTOR = USER_ID.toString();

    @AfterEach
    void clearContext() {
        TestAuthorizationContext.clear();
    }

    @Test
    void createDraftDoesNotRequireSubmitFieldsAndGeneratesProductCode() {
        ErpProductManagementStore store = mock(ErpProductManagementStore.class);
        ErpProductManagementService service = new ErpProductManagementService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:product:write"));
        when(store.existsByCode(TENANT, "PRD202608201234")).thenReturn(false);
        when(store.create(eq(TENANT), eq("PRD202608201234"), any(), eq(ACTOR)))
                .thenReturn(detail(1L, "PRD202608201234", "DRAFT", 1));

        ProductManagementDetailView created = service.create(new ProductManagementCommand(
                false, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null));

        ArgumentCaptor<ProductWrite> command = ArgumentCaptor.forClass(ProductWrite.class);
        verify(store).create(eq(TENANT), eq("PRD202608201234"), command.capture(), eq(ACTOR));
        assertThat(created.productCode()).isEqualTo("PRD202608201234");
        assertThat(command.getValue().productName()).isNull();
        assertThat(command.getValue().submitStatusCode()).isEqualTo("DRAFT");
        assertThat(command.getValue().saleTypeCode()).isEqualTo("SPOT");
        assertThat(command.getValue().shelfStatusCode()).isEqualTo("OFF_SHELF");
        assertThat(command.getValue().variants()).isEmpty();
        assertThat(command.getValue().revision()).isZero();
        verify(store, never()).categoryActive(eq(TENANT), any());
    }

    @Test
    void submitRequiresCoreReferencesAndVariant() {
        ErpProductManagementStore store = mock(ErpProductManagementStore.class);
        ErpProductManagementService service = new ErpProductManagementService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:product:write"));

        ProductManagementCommand command = new ProductManagementCommand(
                true, "酸奶", 1L, 2L, "一箱", "box", null, null,
                null, "spot", "on_shelf", null, null, 3L, null, List.of(), null, null, null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BAD_REQUEST);
        verify(store, never()).create(eq(TENANT), any(), any(), eq(ACTOR));
    }

    @Test
    void submitNormalizesImagesTagsRecommendProductsAndVariants() {
        ErpProductManagementStore store = mock(ErpProductManagementStore.class);
        ErpProductManagementService service = new ErpProductManagementService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:product:write"));
        when(store.existsByCode(TENANT, "PRD202608201234")).thenReturn(false);
        when(store.existsVariantByCode(TENANT, "SKU202608201234")).thenReturn(false);
        when(store.categoryActive(TENANT, 1L)).thenReturn(true);
        when(store.brandActive(TENANT, 2L)).thenReturn(true);
        when(store.warehouseActive(TENANT, 3L)).thenReturn(true);
        when(store.activeTagCodes(TENANT, Set.of("NEW", "HOT"))).thenReturn(Set.of("NEW", "HOT"));
        when(store.activeProductIds(TENANT, Set.of(9L))).thenReturn(Set.of(9L));
        when(store.create(eq(TENANT), eq("PRD202608201234"), any(), eq(ACTOR)))
                .thenReturn(detail(1L, "PRD202608201234", "SUBMITTED", 1));

        service.create(new ProductManagementCommand(true, " 酸奶 ", 1L, 2L, " 一箱 ", " box ",
                new BigDecimal("1"), true, new BigDecimal("2"), " spot ", " on_shelf ",
                List.of(" new ", "hot", "NEW"), null, 3L,
                List.of(new ProductImageCommand(TENANT + "/products/main.png", null, null),
                        new ProductImageCommand(TENANT + "/products/detail.png", "detail", 5)),
                List.of(new ProductVariantCommand(null, " 原味/箱 ", null, new BigDecimal("15.00"),
                        new BigDecimal("18.00"), new BigDecimal("10.00"), null, null, null, true, " 默认 ")),
                List.of(9L), " 商品备注 ", null));

        ArgumentCaptor<ProductWrite> command = ArgumentCaptor.forClass(ProductWrite.class);
        verify(store).create(eq(TENANT), eq("PRD202608201234"), command.capture(), eq(ACTOR));
        ProductWrite write = command.getValue();
        assertThat(write.productName()).isEqualTo("酸奶");
        assertThat(write.unitCode()).isEqualTo("BOX");
        assertThat(write.saleTypeCode()).isEqualTo("SPOT");
        assertThat(write.shelfStatusCode()).isEqualTo("ON_SHELF");
        assertThat(write.tagCodes()).containsExactly("NEW", "HOT");
        assertThat(write.images()).hasSize(2);
        assertThat(write.images().get(0).imageTypeCode()).isEqualTo("MAIN");
        assertThat(write.images().get(0).ordinal()).isZero();
        assertThat(write.images().get(1).imageTypeCode()).isEqualTo("DETAIL");
        assertThat(write.variants()).hasSize(1);
        assertThat(write.variants().get(0).variantCode()).isEqualTo("SKU202608201234");
        assertThat(write.variants().get(0).unitCode()).isEqualTo("BOX");
        assertThat(write.variants().get(0).salePrice()).isEqualByComparingTo("15.00");
        assertThat(write.recommendProductIds()).containsExactly(9L);
        assertThat(write.submitStatusCode()).isEqualTo("SUBMITTED");
    }

    @Test
    void listUsesIndependentFiltersWithoutKeywordAggregation() {
        ErpProductManagementStore store = mock(ErpProductManagementStore.class);
        ErpProductManagementService service = new ErpProductManagementService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:product:read"));
        when(store.products(eq(TENANT), eq(0), eq(20), any()))
                .thenReturn(new MasterDataPageView<ProductManagementSummaryView>(0, 0, 20, List.of()));

        service.products(0, 20, " prd ", " 酸奶 ", 1L, 2L,
                " box ", " spot ", " on_shelf ", " submitted ", 3L);

        ArgumentCaptor<ProductSearchCriteria> criteria = ArgumentCaptor.forClass(ProductSearchCriteria.class);
        verify(store).products(eq(TENANT), eq(0), eq(20), criteria.capture());
        assertThat(criteria.getValue().productCode()).isEqualTo("prd");
        assertThat(criteria.getValue().productName()).isEqualTo("酸奶");
        assertThat(criteria.getValue().categoryId()).isEqualTo(1L);
        assertThat(criteria.getValue().brandId()).isEqualTo(2L);
        assertThat(criteria.getValue().unitCode()).isEqualTo("BOX");
        assertThat(criteria.getValue().saleTypeCode()).isEqualTo("SPOT");
        assertThat(criteria.getValue().shelfStatusCode()).isEqualTo("ON_SHELF");
        assertThat(criteria.getValue().submitStatusCode()).isEqualTo("SUBMITTED");
        assertThat(criteria.getValue().defaultWarehouseId()).isEqualTo(3L);
    }

    @Test
    void deleteUsesLogicDeleteStoreWithOptimisticRevision() {
        ErpProductManagementStore store = mock(ErpProductManagementStore.class);
        ErpProductManagementService service = new ErpProductManagementService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:product:write"));

        service.delete(7L, 2);

        verify(store).delete(TENANT, 7L, 2, ACTOR);
    }

    @Test
    void updateRequiresRevision() {
        ErpProductManagementStore store = mock(ErpProductManagementStore.class);
        ErpProductManagementService service = new ErpProductManagementService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:product:write"));

        assertThatThrownBy(() -> service.update(1L, new ProductManagementCommand(
                false, "酸奶", null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BAD_REQUEST);
        verify(store, never()).update(eq(TENANT), eq(1L), any(), eq(ACTOR));
    }

    private static BusinessCodeGenerator fixedGenerator() {
        return new BusinessCodeGenerator(
                Clock.fixed(Instant.parse("2026-08-20T03:00:00Z"), ZoneId.of("Asia/Shanghai")),
                ignored -> "1234");
    }

    private static ProductManagementDetailView detail(Long id, String productCode,
                                                      String submitStatusCode, int revision) {
        return new ProductManagementDetailView(id, productCode, "酸奶", null, null, null, null,
                null, null, null, false, null, null, null, List.of(), null,
                null, null, List.of(), List.of(), List.of(), submitStatusCode, null, revision,
                ACTOR, Instant.now(), ACTOR, Instant.now());
    }

    private static CallerIdentity caller(String permission) {
        return new CallerIdentity("TENANT", USER_ID, TENANT_ID, USER_ID, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("erp"), Set.of(permission));
    }
}
