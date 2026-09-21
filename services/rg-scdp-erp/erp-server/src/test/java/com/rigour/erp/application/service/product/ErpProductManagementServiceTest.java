package com.rigour.erp.application.service.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.erp.api.v1.model.ExternalProductResolveCommand;
import com.rigour.erp.api.v1.model.ExternalProductResolveRowCommand;
import com.rigour.erp.api.v1.model.ExternalProductResolvedView;
import com.rigour.erp.api.v1.model.ExternalProductRowCommand;
import com.rigour.erp.api.v1.model.ExternalProductSyncCommand;
import com.rigour.erp.api.v1.model.ExternalProductSyncResult;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.api.v1.model.ProductImageCommand;
import com.rigour.erp.api.v1.model.ProductManagementCommand;
import com.rigour.erp.api.v1.model.ProductManagementDetailView;
import com.rigour.erp.api.v1.model.ProductManagementSummaryView;
import com.rigour.erp.api.v1.model.ProductOrdinalCommand;
import com.rigour.erp.api.v1.model.ProductShelfStatusCommand;
import com.rigour.erp.api.v1.model.ProductVariantCommand;
import com.rigour.erp.application.port.out.ErpProductDictionary;
import com.rigour.erp.application.port.out.ErpProductManagementStore;
import com.rigour.erp.application.port.out.ErpProductManagementStore.ProductSearchCriteria;
import com.rigour.erp.application.port.out.ErpProductManagementStore.ProductWrite;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TestAuthorizationContext;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import com.rigour.shared.core.exception.BusinessException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
        ErpProductManagementService service =
                new ErpProductManagementService(
                        store, fixedGenerator(), dictionary());
        TestAuthorizationContext.set(caller("erp:product:write"));
        when(store.existsByCode(TENANT, "PRD202608201234")).thenReturn(false);
        when(store.create(eq(TENANT), eq("PRD202608201234"), any(), eq(ACTOR)))
                .thenReturn(detail(1L, "PRD202608201234", "DRAFT", 1));

        ProductManagementDetailView created =
                service.create(
                        new ProductManagementCommand(
                                false,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null
                        ));

        ArgumentCaptor<ProductWrite> command = ArgumentCaptor.forClass(ProductWrite.class);
        verify(store).create(eq(TENANT), eq("PRD202608201234"), command.capture(), eq(ACTOR));
        assertThat(created.productCode()).isEqualTo("PRD202608201234");
        assertThat(command.getValue().productName()).isNull();
        assertThat(command.getValue().submitStatusCode()).isEqualTo("DRAFT");
        assertThat(command.getValue().saleTypeCode()).isEqualTo("SPOT");
        assertThat(command.getValue().shelfStatusCode()).isEqualTo("OFF_SHELF");
        assertThat(command.getValue().ordinal()).isZero();
        assertThat(command.getValue().variants()).isEmpty();
        assertThat(command.getValue().revision()).isZero();
        verify(store, never()).categoryActive(eq(TENANT), any());
    }

    @Test
    void statisticsUnitLevelOnlyAcceptsThreeLevelsAndBatchIdsAreCapped() {
        var store = mock(ErpProductManagementStore.class);
        var service = new ErpProductManagementService(store, fixedGenerator(), dictionary());
        TestAuthorizationContext.set(caller("erp:product:write"));

        var invalidLevel =
                new ProductManagementCommand(
                                false,
                                "测试商品",
                                null,
                                null,
                                null,
                                "BOX",
                                null,
                                null,
                                null,
                                null,
                                "CASE",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null
                        );
        assertThatThrownBy(() -> service.create(invalidLevel))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("statisticsUnitLevel仅支持BASE、MIDDLE、BIG");

        TestAuthorizationContext.set(caller("erp:product:read"));
        assertThatThrownBy(() -> service.products(
                        0, 20, null, null, null, null, null, null, null, null, null,
                        java.util.stream.LongStream.rangeClosed(1, 201).boxed().toList(), false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("productIds单次最多核对200个商品");
    }

    @Test
    void disabledUnitCannotBeUsedForNewProductDraft() {
        var store = mock(ErpProductManagementStore.class);
        var service = new ErpProductManagementService(store, fixedGenerator(), dictionaryWithoutUnits());
        TestAuthorizationContext.set(caller("erp:product:write"));
        var command =
                new ProductManagementCommand(
                                false,
                                "测试商品",
                                null,
                                null,
                                null,
                                "BOX",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null
                        );
        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已停用");
        verify(store, never()).create(any(), any(), any(), any());
    }

    @Test
    void submitRequiresCoreReferencesAndVariant() {
        ErpProductManagementStore store = mock(ErpProductManagementStore.class);
        ErpProductManagementService service =
                new ErpProductManagementService(
                        store, fixedGenerator(), dictionary());
        TestAuthorizationContext.set(caller("erp:product:write"));

        ProductManagementCommand command =
                new ProductManagementCommand(
                                true,
                                "酸奶",
                                1L,
                                2L,
                                "一箱",
                                "box",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                "spot",
                                "on_shelf",
                                null,
                                null,
                                null,
                                3L,
                                null,
                                List.of(),
                                null,
                                null,
                                null
                        );

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(store, never()).create(eq(TENANT), any(), any(), eq(ACTOR));
    }

    @Test
    void submitNormalizesImagesTagsRecommendProductsAndVariants() {
        ErpProductManagementStore store = mock(ErpProductManagementStore.class);
        ErpProductManagementService service =
                new ErpProductManagementService(
                        store, fixedGenerator(), dictionary());
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

        service.create(
                new ProductManagementCommand(
                                true,
                                " 酸奶 ",
                                1L,
                                2L,
                                " 一箱 ",
                                " box ",
                                null,
                                null,
                                null,
                                null,
                                null,
                                new BigDecimal("1"),
                                true,
                                new BigDecimal("2"),
                                " spot ",
                                " on_shelf ",
                                30,
                                List.of(" new ", "hot", "NEW"),
                                null,
                                3L,
                                List.of(
                                new ProductImageCommand(TENANT + "/products/main.png", null, null),
                                new ProductImageCommand(
                                        TENANT + "/products/detail.png", "detail", 5)),
                                List.of(
                                new ProductVariantCommand(
                                        null,
                                        " 原味/箱 ",
                                        null,
                                        new BigDecimal("15.00"),
                                        new BigDecimal("18.00"),
                                        new BigDecimal("10.00"),
                                        null,
                                        null,
                                        null,
                                        true,
                                        " 默认 ")),
                                List.of(9L),
                                " 商品备注 ",
                                null
                        ));

        ArgumentCaptor<ProductWrite> command = ArgumentCaptor.forClass(ProductWrite.class);
        verify(store).create(eq(TENANT), eq("PRD202608201234"), command.capture(), eq(ACTOR));
        ProductWrite write = command.getValue();
        assertThat(write.productName()).isEqualTo("酸奶");
        assertThat(write.unitCode()).isEqualTo("BOX");
        assertThat(write.saleTypeCode()).isEqualTo("SPOT");
        assertThat(write.shelfStatusCode()).isEqualTo("ON_SHELF");
        assertThat(write.ordinal()).isEqualTo(30);
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
        ErpProductManagementService service =
                new ErpProductManagementService(
                        store, fixedGenerator(), dictionary());
        TestAuthorizationContext.set(caller("erp:product:read"));
        when(store.products(eq(TENANT), eq(0), eq(20), any(), eq(true)))
                .thenReturn(
                        new MasterDataPageView<ProductManagementSummaryView>(0, 0, 20, List.of()));

        service.products(
                0, 20, " prd ", " 酸奶 ", 1L, 2L, " box ", " spot ", " on_shelf ", " submitted ", 3L, null,
                true);

        ArgumentCaptor<ProductSearchCriteria> criteria =
                ArgumentCaptor.forClass(ProductSearchCriteria.class);
        verify(store).products(eq(TENANT), eq(0), eq(20), criteria.capture(), eq(true));
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
        ErpProductManagementService service =
                new ErpProductManagementService(
                        store, fixedGenerator(), dictionary());
        TestAuthorizationContext.set(caller("erp:product:write"));

        service.delete(7L, 2);

        verify(store).delete(TENANT, 7L, 2, ACTOR);
    }

    @Test
    void resolveExternalProductsDelegatesNormalizedRowsToStore() {
        ErpProductManagementStore store = mock(ErpProductManagementStore.class);
        ErpProductManagementService service =
                new ErpProductManagementService(
                        store, fixedGenerator(), dictionary());
        TestAuthorizationContext.set(serviceCaller("erp:product:sync"));
        ExternalProductResolvedView resolved =
                new ExternalProductResolvedView(
                        "line-1",
                        2001L,
                        3001L,
                        "PRD202609010001",
                        "SKU202609010001",
                        "酸辣粉",
                        "箱",
                        "BOX",
                        "BOX",
                        "DINGHUOBAO",
                        "PRODUCT_NAME_EXACT",
                        95,
                        "MATCHED",
                        "已匹配");
        when(store.resolveExternalProducts(eq(TENANT), eq("DINGHUOBAO"), any()))
                .thenReturn(List.of(resolved));

        List<ExternalProductResolvedView> result =
                service.resolveExternalProducts(
                        new ExternalProductResolveCommand(
                                " dinghuobao ",
                                List.of(
                                        new ExternalProductResolveRowCommand(
                                                " line-1 ", " p1001 ", null, " 酸辣粉 ", " 箱 "))));

        ArgumentCaptor<List<ExternalProductResolveRowCommand>> rows =
                ArgumentCaptor.forClass(List.class);
        verify(store).resolveExternalProducts(eq(TENANT), eq("DINGHUOBAO"), rows.capture());
        assertThat(result).containsExactly(resolved);
        assertThat(rows.getValue())
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.referenceId()).isEqualTo("line-1");
                            assertThat(row.productCode()).isEqualTo("p1001");
                            assertThat(row.productName()).isEqualTo("酸辣粉");
                            assertThat(row.specification()).isEqualTo("箱");
                        });
    }

    @Test
    void syncExternalProductsUsesSystemAuditActorForServiceCaller() {
        ErpProductManagementStore store = mock(ErpProductManagementStore.class);
        ErpProductManagementService service =
                new ErpProductManagementService(
                        store, fixedGenerator(), dictionary());
        TestAuthorizationContext.set(serviceCaller("erp:product:sync"));
        when(store.syncExternalProducts(eq(TENANT), eq("FEISHU"), any(), eq("SYSTEM"), any()))
                .thenReturn(new ExternalProductSyncResult(1, 0, 0, 1, 0, List.of(), List.of()));

        service.syncExternalProducts(
                new ExternalProductSyncCommand(
                        " feishu ",
                        List.of(
                                new ExternalProductRowCommand(
                                        null,
                                        " default ",
                                        " source-product-1 ",
                                        " SPU-1 ",
                                        " 酸辣粉 ",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        Instant.parse("2026-09-01T00:00:00Z"),
                                        null,
                                        "hash-1",
                                        "{}"))));

        ArgumentCaptor<List<ExternalProductRowCommand>> rows = ArgumentCaptor.forClass(List.class);
        verify(store)
                .syncExternalProducts(
                        eq(TENANT), eq("FEISHU"), rows.capture(), eq("SYSTEM"), any());
        assertThat(rows.getValue())
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.sourceTenantKey()).isEqualTo("default");
                            assertThat(row.sourceProductId()).isEqualTo("source-product-1");
                            assertThat(row.productName()).isEqualTo("酸辣粉");
                        });
    }

    @Test
    void updateRequiresRevision() {
        ErpProductManagementStore store = mock(ErpProductManagementStore.class);
        ErpProductManagementService service =
                new ErpProductManagementService(
                        store, fixedGenerator(), dictionary());
        TestAuthorizationContext.set(caller("erp:product:write"));

        assertThatThrownBy(
                        () ->
                                service.update(
                                        1L,
                                        new ProductManagementCommand(
                                false,
                                "酸奶",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null
                        )))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(store, never()).update(eq(TENANT), eq(1L), any(), eq(ACTOR));
    }

    @Test
    void shelfStatusToggleRequiresRevisionAndNormalizesCode() {
        ErpProductManagementStore store = mock(ErpProductManagementStore.class);
        ErpProductManagementService service =
                new ErpProductManagementService(
                        store, fixedGenerator(), dictionary());
        TestAuthorizationContext.set(caller("erp:product:write"));
        when(store.updateShelfStatus(TENANT, 1L, "ON_SHELF", 4, ACTOR))
                .thenReturn(detail(1L, "PRD202608201234", "SUBMITTED", 5));

        ProductManagementDetailView updated =
                service.updateShelfStatus(1L, new ProductShelfStatusCommand(" on_shelf ", 4));

        assertThat(updated.revision()).isEqualTo(5);
        verify(store).updateShelfStatus(TENANT, 1L, "ON_SHELF", 4, ACTOR);

        assertThatThrownBy(() -> service.updateShelfStatus(1L, new ProductShelfStatusCommand("ON_SHELF", null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        assertThatThrownBy(() -> service.updateShelfStatus(1L, new ProductShelfStatusCommand(null, 4)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(store, never()).updateShelfStatus(eq(TENANT), eq(1L), eq(null), anyInt(), eq(ACTOR));
    }

    @Test
    void ordinalUpdateRejectsMissingValueAndKeepsOptimisticLock() {
        ErpProductManagementStore store = mock(ErpProductManagementStore.class);
        ErpProductManagementService service =
                new ErpProductManagementService(
                        store, fixedGenerator(), dictionary());
        TestAuthorizationContext.set(caller("erp:product:write"));
        when(store.updateOrdinal(TENANT, 1L, 20, 3, ACTOR))
                .thenReturn(detail(1L, "PRD202608201234", "SUBMITTED", 4));

        ProductManagementDetailView updated =
                service.updateOrdinal(1L, new ProductOrdinalCommand(20, 3));

        assertThat(updated.revision()).isEqualTo(4);
        verify(store).updateOrdinal(TENANT, 1L, 20, 3, ACTOR);

        assertThatThrownBy(() -> service.updateOrdinal(1L, new ProductOrdinalCommand(null, 3)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ordinal不能为空");
        assertThatThrownBy(() -> service.updateOrdinal(1L, new ProductOrdinalCommand(-1, 3)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ordinal必须大于等于0");
        assertThatThrownBy(() -> service.updateOrdinal(1L, new ProductOrdinalCommand(20, 0)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("revision必须大于0");
    }

    @Test
    void saleTypeAndShelfStatusOutsideDictionaryAreRejected() {
        ErpProductManagementStore store = mock(ErpProductManagementStore.class);
        ErpProductManagementService service =
                new ErpProductManagementService(store, fixedGenerator(), dictionary());
        TestAuthorizationContext.set(caller("erp:product:write"));

        assertThatThrownBy(
                        () ->
                                service.create(
                                        new ProductManagementCommand(
                                false,
                                "酸奶",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                "GROUP_BUY",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null
                        )))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("售卖类型不存在或已停用");

        assertThatThrownBy(
                        () ->
                                service.create(
                                        new ProductManagementCommand(
                                false,
                                "酸奶",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                "PRE_ORDER",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null
                        )))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上架状态不存在或已停用");

        verify(store, never()).create(any(), any(), any(), any());
    }

    @Test
    void shelfStatusToggleRejectsCodeOutsideDictionary() {
        ErpProductManagementStore store = mock(ErpProductManagementStore.class);
        ErpProductManagementService service =
                new ErpProductManagementService(store, fixedGenerator(), dictionary());
        TestAuthorizationContext.set(caller("erp:product:write"));

        assertThatThrownBy(
                        () ->
                                service.updateShelfStatus(
                                        1L, new ProductShelfStatusCommand("PRE_ORDER", 4)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上架状态不存在或已停用");
        verify(store, never()).updateShelfStatus(any(), any(), any(), anyInt(), any());
    }

    /** 商品单位、售卖类型、上架状态三类字典都齐全的租户。 */
    private static ErpProductDictionary dictionary() {
        return (tenant, dictionaryCode) ->
                switch (dictionaryCode) {
                    case "PRODUCT_UNIT" -> Set.of("BOX", "PIECE");
                    case "PRODUCT_SALE_TYPE" -> Set.of("SPOT", "PRE_SALE", "STOP_SALE");
                    case "PRODUCT_SHELF_STATUS" -> Set.of("ON_SHELF", "OFF_SHELF");
                    default -> Set.of();
                };
    }

    /** 只缺商品单位字典项的租户，用于验证停用单位被拒绝。 */
    private static ErpProductDictionary dictionaryWithoutUnits() {
        return (tenant, dictionaryCode) ->
                dictionaryCode.equals("PRODUCT_UNIT") ? Set.of() : dictionary().validCodes(tenant, dictionaryCode);
    }

    private static BusinessCodeGenerator fixedGenerator() {
        return new BusinessCodeGenerator(
                Clock.fixed(Instant.parse("2026-08-20T03:00:00Z"), ZoneId.of("Asia/Shanghai")),
                ignored -> "1234");
    }

    private static ProductManagementDetailView detail(
            Long id, String productCode, String submitStatusCode, int revision) {
        return new ProductManagementDetailView(
                id,
                productCode,
                "酸奶",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                submitStatusCode,
                null,
                revision,
                ACTOR,
                Instant.now(),
                ACTOR,
                Instant.now());
    }

    private static CallerIdentity caller(String permission) {
        return new CallerIdentity(
                "TENANT",
                USER_ID,
                TENANT_ID,
                USER_ID,
                null,
                UUID.randomUUID(),
                0,
                0,
                0,
                Set.of("erp"),
                Set.of(permission));
    }

    private static CallerIdentity serviceCaller(String permission) {
        return new CallerIdentity(
                "SERVICE",
                USER_ID,
                TENANT_ID,
                null,
                null,
                UUID.randomUUID(),
                0,
                0,
                0,
                Set.of("erp"),
                Set.of(permission));
    }
}
