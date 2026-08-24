package com.rigour.erp.application.service.inventory;

import com.rigour.erp.api.v1.model.InternalInventoryWarehouseCommand;
import com.rigour.erp.api.v1.model.InternalInventoryWarehouseView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.port.out.ErpInventoryWarehouseStore;
import com.rigour.erp.application.port.out.ErpInventoryWarehouseStore.WarehouseSearchCriteria;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TestAuthorizationContext;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ErpInventoryWarehouseServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb701-0000-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("019fb701-0000-7000-8000-000000000002");
    private static final String TENANT = TENANT_ID.toString();
    private static final String ACTOR = USER_ID.toString();

    @AfterEach
    void clearContext() {
        TestAuthorizationContext.clear();
    }

    @Test
    void createWarehouseGeneratesCodeAndUsesBusinessDefaults() {
        ErpInventoryWarehouseStore store = mock(ErpInventoryWarehouseStore.class);
        ErpInventoryWarehouseService service = new ErpInventoryWarehouseService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:product:write"));
        when(store.existsByCode(TENANT, "WH202608201234")).thenReturn(false);
        when(store.create(eq(TENANT), eq("WH202608201234"), any(), eq(ACTOR)))
                .thenReturn(new InternalInventoryWarehouseView(1L, "WH202608201234", "上海默认仓",
                        "SHANGHAI", "CITY", false, null, null, null, "ACTIVE", null,
                        1, ACTOR, Instant.now(), ACTOR, Instant.now()));

        service.create(new InternalInventoryWarehouseCommand(" 上海默认仓 ", " shanghai ",
                null, null, null, null, null, null, null, null));

        ArgumentCaptor<InternalInventoryWarehouseCommand> command =
                ArgumentCaptor.forClass(InternalInventoryWarehouseCommand.class);
        verify(store).create(eq(TENANT), eq("WH202608201234"), command.capture(), eq(ACTOR));
        assertThat(command.getValue().warehouseName()).isEqualTo("上海默认仓");
        assertThat(command.getValue().regionCode()).isEqualTo("SHANGHAI");
        assertThat(command.getValue().warehouseTypeCode()).isEqualTo("CITY");
        assertThat(command.getValue().defaultFlag()).isFalse();
        assertThat(command.getValue().statusCode()).isEqualTo("ACTIVE");
    }

    @Test
    void listWarehouseUsesDedicatedFilters() {
        ErpInventoryWarehouseStore store = mock(ErpInventoryWarehouseStore.class);
        ErpInventoryWarehouseService service = new ErpInventoryWarehouseService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:product:read"));
        when(store.warehouses(eq(TENANT), eq(0), eq(20), any()))
                .thenReturn(new MasterDataPageView<>(0, 0, 20, List.of()));

        service.warehouses(0, 20, " WH ", " 城市仓 ", " east ", true, " active ");

        ArgumentCaptor<WarehouseSearchCriteria> criteria =
                ArgumentCaptor.forClass(WarehouseSearchCriteria.class);
        verify(store).warehouses(eq(TENANT), eq(0), eq(20), criteria.capture());
        assertThat(criteria.getValue().warehouseCode()).isEqualTo("WH");
        assertThat(criteria.getValue().warehouseName()).isEqualTo("城市仓");
        assertThat(criteria.getValue().regionCode()).isEqualTo("EAST");
        assertThat(criteria.getValue().defaultFlag()).isTrue();
        assertThat(criteria.getValue().statusCode()).isEqualTo("ACTIVE");
    }

    @Test
    void deleteWarehouseRequiresRevisionForLogicalDelete() {
        ErpInventoryWarehouseStore store = mock(ErpInventoryWarehouseStore.class);
        ErpInventoryWarehouseService service = new ErpInventoryWarehouseService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:product:write"));

        assertThatThrownBy(() -> service.delete(1L, 0))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BAD_REQUEST);
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
