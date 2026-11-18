package com.rigour.merchant.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.rigour.merchant.api.v1.model.StorePoolPolicyModels.Publish;
import com.rigour.merchant.application.port.out.*;
import com.rigour.shared.core.exception.RequestValidationException;
import org.junit.jupiter.api.Test;
import java.util.UUID;

class StorePoolPolicyValidationTest {
    StoreReferenceClient references=mock(StoreReferenceClient.class);
    StorePoolStore store=mock(StorePoolStore.class);
    StorePoolService service=new StorePoolService(references,mock(CrmEmployeeClient.class),store,mock(StorePoiClient.class));
    @Test void rejectsMissingAndInvalidDurationsBeforePublication(){
        for(int field=0;field<6;field++) {
            int[] values={36,11,90,12,120,6};values[field]=0;
            var c=new Publish(1,UUID.randomUUID().toString(),values[0],values[1],values[2],values[3],values[4],values[5],"修改");
            assertThatThrownBy(()->service.publishPoolPolicy(c)).isInstanceOf(RequestValidationException.class);
        }
        assertThatThrownBy(()->service.publishPoolPolicy(new Publish(1,UUID.randomUUID().toString(),65536,1,1,1,1,1,"修改"))).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(()->service.publishPoolPolicy(new Publish(1,UUID.randomUUID().toString(),1,1,1,1,1,1," "))).isInstanceOf(RequestValidationException.class);
        verifyNoInteractions(store,references);
    }
    @Test void readAndPublicationRequireManagementAuthority(){
        when(references.access("crm:store:manage")).thenThrow(new com.rigour.shared.context.AuthorizationDeniedException("crm:store:manage"));
        assertThatThrownBy(service::poolPolicy).isInstanceOf(com.rigour.shared.context.AuthorizationDeniedException.class);
        assertThatThrownBy(service::poolPolicyHistory).isInstanceOf(com.rigour.shared.context.AuthorizationDeniedException.class);
        assertThatThrownBy(()->service.publishPoolPolicy(new Publish(1,UUID.randomUUID().toString(),1,1,1,1,1,1,"修改"))).isInstanceOf(com.rigour.shared.context.AuthorizationDeniedException.class);
        verifyNoInteractions(store);
    }
}
