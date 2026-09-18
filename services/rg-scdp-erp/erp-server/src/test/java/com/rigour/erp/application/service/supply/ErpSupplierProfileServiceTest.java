package com.rigour.erp.application.service.supply;

import com.rigour.erp.api.v1.model.InternalSupplierProfileCommand;
import com.rigour.erp.api.v1.model.InternalSupplierProfileView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.application.port.out.ErpSupplierProfileStore;
import com.rigour.erp.application.port.out.ErpSupplierProfileStore.SupplierSearchCriteria;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ErpSupplierProfileServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("019fb700-2000-7000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("019fb700-2000-7000-8000-000000000002");
    private static final String TENANT = TENANT_ID.toString();
    private static final String ACTOR = USER_ID.toString();

    @AfterEach
    void clearContext() {
        TestAuthorizationContext.clear();
    }

    @Test
    void createGeneratesSupplierCodeAndNormalizesCommand() {
        ErpSupplierProfileStore store = mock(ErpSupplierProfileStore.class);
        ErpSupplierProfileService service = new ErpSupplierProfileService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:supply:write"));
        when(store.existsByCode(TENANT, "SUP202608201234")).thenReturn(false);
        when(store.create(eq(TENANT), eq("SUP202608201234"), any(), eq(ACTOR)))
                .thenReturn(view(1L, "SUP202608201234", "ACTIVE", 1));

        InternalSupplierProfileView created = service.create(new InternalSupplierProfileCommand(
                " 供应商一 ", " 张三 ", " 13800138000 ", " 上海 ", " 测试银行 ",
                " 6222000012345678 ", null, " 默认供应商 ", null));

        ArgumentCaptor<InternalSupplierProfileCommand> command =
                ArgumentCaptor.forClass(InternalSupplierProfileCommand.class);
        verify(store).create(eq(TENANT), eq("SUP202608201234"), command.capture(), eq(ACTOR));
        assertThat(created.supplierCode()).isEqualTo("SUP202608201234");
        assertThat(command.getValue().supplierName()).isEqualTo("供应商一");
        assertThat(command.getValue().contactName()).isEqualTo("张三");
        assertThat(command.getValue().contactPhone()).isEqualTo("13800138000");
        assertThat(command.getValue().statusCode()).isEqualTo("ACTIVE");
        assertThat(command.getValue().revision()).isZero();
    }

    @Test
    void listUsesIndependentFiltersWithoutKeywordAggregation() {
        ErpSupplierProfileStore store = mock(ErpSupplierProfileStore.class);
        ErpSupplierProfileService service = new ErpSupplierProfileService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:supply:read"));
        when(store.suppliers(eq(TENANT), eq(0), eq(20), any()))
                .thenReturn(new MasterDataPageView<>(0, 0, 20, List.of()));

        service.suppliers(0, 20, " sup ", " 供应商 ", " 138 ", " disabled ");

        ArgumentCaptor<SupplierSearchCriteria> criteria = ArgumentCaptor.forClass(SupplierSearchCriteria.class);
        verify(store).suppliers(eq(TENANT), eq(0), eq(20), criteria.capture());
        assertThat(criteria.getValue().supplierCode()).isEqualTo("sup");
        assertThat(criteria.getValue().supplierName()).isEqualTo("供应商");
        assertThat(criteria.getValue().contactPhone()).isEqualTo("138");
        assertThat(criteria.getValue().statusCode()).isEqualTo("DISABLED");
    }

    @Test
    void updateRequiresOptimisticRevision() {
        ErpSupplierProfileStore store = mock(ErpSupplierProfileStore.class);
        ErpSupplierProfileService service = new ErpSupplierProfileService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:supply:write"));

        assertThatThrownBy(() -> service.update(1L, new InternalSupplierProfileCommand(
                "供应商一", null, null, null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BAD_REQUEST);
        verify(store, never()).update(eq(TENANT), eq(1L), any(), eq(ACTOR));
    }

    @Test
    void deleteUsesLogicDeleteStoreWithOptimisticRevision() {
        ErpSupplierProfileStore store = mock(ErpSupplierProfileStore.class);
        ErpSupplierProfileService service = new ErpSupplierProfileService(store, fixedGenerator());
        TestAuthorizationContext.set(caller("erp:supply:write"));

        service.delete(7L, 2);

        verify(store).delete(TENANT, 7L, 2, ACTOR);
    }

    private static BusinessCodeGenerator fixedGenerator() {
        return new BusinessCodeGenerator(
                Clock.fixed(Instant.parse("2026-08-20T03:00:00Z"), ZoneId.of("Asia/Shanghai")),
                ignored -> "1234");
    }

    private static InternalSupplierProfileView view(Long id, String supplierCode, String statusCode, int revision) {
        return new InternalSupplierProfileView(id, supplierCode, "供应商一", "张三", "13800138000",
                "上海", "测试银行", "6222000012345678", statusCode, null, revision,
                ACTOR, Instant.now(), ACTOR, Instant.now());
    }

    private static CallerIdentity caller(String permission) {
        return new CallerIdentity("TENANT", USER_ID, TENANT_ID, USER_ID, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("erp"), Set.of(permission));
    }
}
