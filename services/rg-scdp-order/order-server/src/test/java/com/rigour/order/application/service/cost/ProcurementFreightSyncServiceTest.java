package com.rigour.order.application.service.cost;
import com.rigour.order.application.port.out.*;
import com.rigour.shared.context.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import com.rigour.erp.api.v1.model.ProcurementCostSource;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ProcurementFreightSyncServiceTest {
    final ProcurementFreightSource source=mock(ProcurementFreightSource.class);
    final OrderLogisticsCostStore store=mock(OrderLogisticsCostStore.class);
    final ProcurementFreightSyncService service=new ProcurementFreightSyncService(source,store);
    @AfterEach void clear(){TestAuthorizationContext.clear();}
    @Test void aDifferentServiceCannotClaimErpSourceEvenWithTheSamePermission(){
        TestAuthorizationContext.set(new CallerIdentity("SERVICE",UUID.nameUUIDFromBytes("service:another-service".getBytes(StandardCharsets.UTF_8)),UUID.randomUUID(),null,null,UUID.randomUUID(),0,0,0,Set.of(),Set.of("order:logistics-cost:procurement-sync")));
        assertThatThrownBy(()->service.sync("PO1")).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(source,store);
    }
    @Test void signedTenantServiceFetchesAuthoritativeSourceWithinItsOwnTenant(){
        UUID tenant=UUID.randomUUID();
        TestAuthorizationContext.set(new CallerIdentity("SERVICE",UUID.nameUUIDFromBytes("service:rigour-erp-core-service".getBytes(StandardCharsets.UTF_8)),tenant,null,null,UUID.randomUUID(),0,0,0,Set.of(),Set.of("order:logistics-cost:procurement-sync")));
        when(source.source(tenant.toString(),"PO1")).thenThrow(new IllegalStateException("source unavailable"));
        assertThatThrownBy(()->service.sync("PO1")).hasMessageContaining("source unavailable");
        verify(source).source(tenant.toString(),"PO1");verifyNoInteractions(store);
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings=" ")
    void missingWarehouseCityIsAnActionableValidationErrorAndCannotCreateFalseAttribution(String city){
        UUID tenant=UUID.randomUUID();
        TestAuthorizationContext.set(new CallerIdentity("SERVICE",UUID.nameUUIDFromBytes("service:rigour-erp-core-service".getBytes(StandardCharsets.UTF_8)),tenant,null,null,UUID.randomUUID(),0,0,0,Set.of(),Set.of("order:logistics-cost:procurement-sync")));
        var valid=ProcurementFreightDraftTest.source("BUYER",java.math.BigDecimal.TEN);
        when(source.source(tenant.toString(),"PO1")).thenReturn(new ProcurementCostSource(
                valid.procurementNo(),valid.revision(),valid.purchaseDate(),valid.supplierCode(),valid.supplierName(),
                valid.supplierAddress(),valid.supplierContact(),valid.supplierPhone(),valid.warehouseId(),
                valid.warehouseCode(),valid.warehouseName(),city,valid.receiverName(),valid.receiverPhone(),
                valid.receivingAddress(),valid.freightBearerCode(),valid.freightAmount()));
        assertThatThrownBy(()->service.sync("PO1")).isInstanceOfSatisfying(BusinessException.class,
                error->assertThat(error.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED))
                .hasMessageContaining("请维护仓库档案后重试");
        verifyNoInteractions(store);
    }
}
