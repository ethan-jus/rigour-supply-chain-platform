package com.rigour.erp.application.service.supply;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.erp.api.v1.model.InternalProcurementOrderDetailView;
import com.rigour.erp.application.port.out.*;
import com.rigour.erp.domain.model.supply.ProcurementContractKey;
import com.rigour.shared.context.*;
import com.rigour.shared.core.exception.BusinessException;

import org.junit.jupiter.api.*;

import java.util.*;

class ProcurementContractServiceTest {
    UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
    ErpProcurementOrderStore orders = mock(ErpProcurementOrderStore.class);
    ProcurementContractStorage files = mock(ProcurementContractStorage.class);
    ProcurementContractService service = new ProcurementContractService(orders, files);

    @BeforeEach
    void identity() {
        TestAuthorizationContext.set(
                new CallerIdentity(
                        "TENANT",
                        user,
                        tenant,
                        user,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of("erp"),
                        Set.of("erp:supply:read", "erp:supply:write")));
    }

    @AfterEach
    void clear() {
        TestAuthorizationContext.clear();
    }

    @Test
    void confirmedOrderCannotUploadAReplacement() {
        var order = mock(InternalProcurementOrderDetailView.class);
        when(order.statusCode()).thenReturn("SUBMITTED");
        when(order.confirmationStatusCode()).thenReturn("CONFIRMED");
        when(orders.procurementOrder(tenant.toString(), 1L)).thenReturn(Optional.of(order));
        assertThatThrownBy(
                        () ->
                                service.upload(
                                        1L, "合同.pdf", "application/pdf", "%PDF-1.7".getBytes()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已确认");
        verifyNoInteractions(files);
    }

    @Test
    void rejectsFakePdfAndUploadsValidContentWithTenantAndActor() {
        assertThatThrownBy(
                        () ->
                                service.upload(
                                        null,
                                        "合同.pdf",
                                        "application/pdf",
                                        "<html>content</html>".getBytes()))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(files);
        byte[] content = "%PDF-1.7".getBytes();
        when(files.upload(tenant.toString(), user.toString(), "application/pdf", content))
                .thenReturn("key");
        assertThat(service.upload(null, "合同.pdf", "application/pdf", content)).isEqualTo("key");
    }

    @Test
    void anotherTenantOrActorsNewFileCannotBeBound() {
        String key = tenant + "/erp-procurement-contracts/" + user + "/" + "a".repeat(32) + ".pdf";
        assertThat(ProcurementContractKey.writable(tenant.toString(), user.toString(), key, null))
                .isTrue();
        assertThat(
                        ProcurementContractKey.writable(
                                UUID.randomUUID().toString(), user.toString(), key, null))
                .isFalse();
        assertThat(
                        ProcurementContractKey.writable(
                                tenant.toString(), UUID.randomUUID().toString(), key, null))
                .isFalse();
        assertThat(
                        ProcurementContractKey.writable(
                                tenant.toString(), UUID.randomUUID().toString(), key, key))
                .isTrue();
        assertThat(
                        ProcurementContractKey.writable(
                                tenant.toString(), user.toString(), key + "/../x", null))
                .isFalse();
    }
}
