package com.rigour.order.application.service.cost;
import com.rigour.shared.context.*;
import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class ProcurementFreightSyncServiceTest {
    final ProcurementFreightSyncService service = new ProcurementFreightSyncService();
    @AfterEach void clear() { TestAuthorizationContext.clear(); }
    void actor(String name) {
        TestAuthorizationContext.set(new CallerIdentity("SERVICE",
                UUID.nameUUIDFromBytes(("service:"+name).getBytes(StandardCharsets.UTF_8)),
                UUID.randomUUID(),null,null,UUID.randomUUID(),0,0,0,Set.of(),
                Set.of("order:logistics-cost:procurement-sync")));
    }
    @Test void anOldErpPublisherIsAcknowledgedWithoutCreatingLogisticsCosts() {
        actor("rigour-erp-core-service");
        assertThat(service.sync("PO1")).isEqualTo("ERP_PURCHASE_COST");
        assertThat(service.sync("PO1")).isEqualTo("ERP_PURCHASE_COST");
    }
    @Test void compatibilityEndpointStillRejectsOtherServicesAndInvalidCodes() {
        actor("another-service");
        assertThatThrownBy(() -> service.sync("PO1")).isInstanceOf(AuthorizationDeniedException.class);
        actor("rigour-erp-core-service");
        assertThatThrownBy(() -> service.sync("../PO1")).isInstanceOf(IllegalArgumentException.class);
    }
}
