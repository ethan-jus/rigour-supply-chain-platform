package com.rigour.erp.application.service.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.erp.api.v1.model.CustomerTypePriceImportCommand;
import com.rigour.erp.api.v1.model.CustomerTypePriceImportItem;
import com.rigour.erp.api.v1.model.CustomerTypePriceImportResult;
import com.rigour.erp.api.v1.model.CustomerTypePriceItem;
import com.rigour.erp.api.v1.model.CustomerTypePriceSyncCommand;
import com.rigour.erp.api.v1.model.CustomerTypePriceView;
import com.rigour.erp.application.port.out.ErpCustomerTypePriceStore;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TestAuthorizationContext;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ErpCustomerTypePriceServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb700-1000-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("019fb700-1000-7000-8000-000000000002");
    private static final String TENANT = TENANT_ID.toString();
    private static final String ACTOR = USER_ID.toString();

    @AfterEach
    void clearContext() {
        TestAuthorizationContext.clear();
    }

    @Test
    void pricesRejectsTooManyProductIds() {
        ErpCustomerTypePriceStore store = mock(ErpCustomerTypePriceStore.class);
        ErpCustomerTypePriceService service = new ErpCustomerTypePriceService(store);
        TestAuthorizationContext.set(caller("erp:product-price:read"));
        List<Long> productIds = new ArrayList<>();
        for (long id = 1; id <= 201; id++) productIds.add(id);

        assertThatThrownBy(() -> service.prices(productIds))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(store, never()).pricesByProductIds(any(), anyList());
    }

    @Test
    void pricesReturnsEmptyWithoutProductIds() {
        ErpCustomerTypePriceStore store = mock(ErpCustomerTypePriceStore.class);
        ErpCustomerTypePriceService service = new ErpCustomerTypePriceService(store);
        TestAuthorizationContext.set(caller("erp:product-price:read"));

        assertThat(service.prices(null)).isEmpty();
        verify(store, never()).pricesByProductIds(any(), anyList());
    }

    @Test
    void pricesDelegatesForKnownProducts() {
        ErpCustomerTypePriceStore store = mock(ErpCustomerTypePriceStore.class);
        ErpCustomerTypePriceService service = new ErpCustomerTypePriceService(store);
        TestAuthorizationContext.set(caller("erp:product-price:read"));
        when(store.pricesByProductIds(eq(TENANT), eq(List.of(9L))))
                .thenReturn(List.of(view(7L, 3L, "CT001", new BigDecimal("10.0000"), 1)));

        List<CustomerTypePriceView> result = service.prices(List.of(9L));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).customerTypeCode()).isEqualTo("CT001");
    }

    @Test
    void syncRejectsDuplicateCustomerTypes() {
        ErpCustomerTypePriceStore store = mock(ErpCustomerTypePriceStore.class);
        ErpCustomerTypePriceService service = new ErpCustomerTypePriceService(store);
        TestAuthorizationContext.set(caller("erp:product-price:write"));
        CustomerTypePriceSyncCommand command = new CustomerTypePriceSyncCommand(List.of(
                new CustomerTypePriceItem("CT001", new BigDecimal("10.0000"), null),
                new CustomerTypePriceItem("CT001", new BigDecimal("8.0000"), null)));

        assertThatThrownBy(() -> service.syncVariant(3L, command))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(store, never()).syncVariantPrices(any(), any(), any(), any());
    }

    @Test
    void syncRejectsNonPositivePrice() {
        ErpCustomerTypePriceStore store = mock(ErpCustomerTypePriceStore.class);
        ErpCustomerTypePriceService service = new ErpCustomerTypePriceService(store);
        TestAuthorizationContext.set(caller("erp:product-price:write"));
        CustomerTypePriceSyncCommand command = new CustomerTypePriceSyncCommand(List.of(
                new CustomerTypePriceItem("CT001", BigDecimal.ZERO, null)));

        assertThatThrownBy(() -> service.syncVariant(3L, command))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(store, never()).syncVariantPrices(any(), any(), any(), any());
    }

    @Test
    void syncNormalizesAndDelegates() {
        ErpCustomerTypePriceStore store = mock(ErpCustomerTypePriceStore.class);
        ErpCustomerTypePriceService service = new ErpCustomerTypePriceService(store);
        TestAuthorizationContext.set(caller("erp:product-price:write"));
        when(store.syncVariantPrices(eq(TENANT), eq(3L), any(), eq(ACTOR)))
                .thenReturn(List.of(view(7L, 3L, "CT001", new BigDecimal("10.0000"), 1)));
        CustomerTypePriceSyncCommand command = new CustomerTypePriceSyncCommand(List.of(
                new CustomerTypePriceItem("CT001", new BigDecimal("10.0000"), "  ")));

        List<CustomerTypePriceView> result = service.syncVariant(3L, command);

        verify(store).syncVariantPrices(eq(TENANT), eq(3L), argThat(items ->
                items.size() == 1
                        && items.get(0).remark() == null
                        && items.get(0).salePrice().compareTo(new BigDecimal("10.0000")) == 0), eq(ACTOR));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(7L);
    }

    @Test
    void syncRequiresWritePermission() {
        ErpCustomerTypePriceStore store = mock(ErpCustomerTypePriceStore.class);
        ErpCustomerTypePriceService service = new ErpCustomerTypePriceService(store);
        TestAuthorizationContext.set(caller("erp:product-price:read"));
        CustomerTypePriceSyncCommand command = new CustomerTypePriceSyncCommand(List.of(
                new CustomerTypePriceItem("CT001", new BigDecimal("10.0000"), null)));

        assertThatThrownBy(() -> service.syncVariant(3L, command))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void importRejectsEmptyItems() {
        ErpCustomerTypePriceStore store = mock(ErpCustomerTypePriceStore.class);
        ErpCustomerTypePriceService service = new ErpCustomerTypePriceService(store);
        TestAuthorizationContext.set(caller("erp:product-price:write"));

        assertThatThrownBy(() -> service.importPrices(new CustomerTypePriceImportCommand(List.of())))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(store, never()).importPrices(any(), any(), any());
    }

    @Test
    void importRejectsDuplicateVariantAndType() {
        ErpCustomerTypePriceStore store = mock(ErpCustomerTypePriceStore.class);
        ErpCustomerTypePriceService service = new ErpCustomerTypePriceService(store);
        TestAuthorizationContext.set(caller("erp:product-price:write"));
        CustomerTypePriceImportCommand command = new CustomerTypePriceImportCommand(List.of(
                new CustomerTypePriceImportItem(3L, "CT001", new BigDecimal("10.0000"), null),
                new CustomerTypePriceImportItem(3L, "CT001", new BigDecimal("9.0000"), null)));

        assertThatThrownBy(() -> service.importPrices(command))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(store, never()).importPrices(any(), any(), any());
    }

    @Test
    void importDelegatesNormalizedItems() {
        ErpCustomerTypePriceStore store = mock(ErpCustomerTypePriceStore.class);
        ErpCustomerTypePriceService service = new ErpCustomerTypePriceService(store);
        TestAuthorizationContext.set(caller("erp:product-price:write"));
        when(store.importPrices(eq(TENANT), any(), eq(ACTOR)))
                .thenReturn(new CustomerTypePriceImportResult(1, 1, 0));
        CustomerTypePriceImportCommand command = new CustomerTypePriceImportCommand(List.of(
                new CustomerTypePriceImportItem(3L, "CT001", new BigDecimal("10.0000"), "  ")));

        CustomerTypePriceImportResult result = service.importPrices(command);

        verify(store).importPrices(eq(TENANT), argThat(items ->
                items.size() == 1
                        && items.get(0).remark() == null
                        && items.get(0).productVariantId() == 3L), eq(ACTOR));
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.updated()).isZero();
    }

    @Test
    void importRequiresWritePermission() {
        ErpCustomerTypePriceStore store = mock(ErpCustomerTypePriceStore.class);
        ErpCustomerTypePriceService service = new ErpCustomerTypePriceService(store);
        TestAuthorizationContext.set(caller("erp:product-price:read"));
        CustomerTypePriceImportCommand command = new CustomerTypePriceImportCommand(List.of(
                new CustomerTypePriceImportItem(3L, "CT001", new BigDecimal("10.0000"), null)));

        assertThatThrownBy(() -> service.importPrices(command))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    private static CustomerTypePriceView view(
            Long id, Long variantId, String typeCode, BigDecimal price, int revision) {
        return new CustomerTypePriceView(id, 9L, "PRD202601010001", "测试商品", variantId,
                "SKU202601010001", "S,上海", typeCode, price, null, revision, ACTOR,
                Instant.parse("2026-09-18T03:00:00Z"), ACTOR, Instant.parse("2026-09-18T03:00:00Z"));
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
}
