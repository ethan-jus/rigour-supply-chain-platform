package com.rigour.merchant.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.rigour.merchant.api.v1.model.StoreContactModels.*;
import com.rigour.merchant.application.port.out.StoreContactStore;
import com.rigour.shared.core.exception.RequestValidationException;
import com.rigour.shared.context.AuthorizationDeniedException;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StoreContactServiceTest {
    StorePoolService pools=mock(StorePoolService.class);
    StoreContactStore contacts=mock(StoreContactStore.class);
    StoreContactService service=new StoreContactService(pools,contacts,Clock.systemUTC());
    @Test void publicViewCannotExposeContacts(){assertThatThrownBy(()->service.list("1","PUBLIC")).isInstanceOf(AuthorizationDeniedException.class);verifyNoInteractions(pools,contacts);}
    @Test void receptionistMayDeclinePhoneWithoutInventingVerifiedContact(){service.create("1","MINE",new Create(UUID.randomUUID().toString(),"前台小李","店员",false,null,"微信昵称"));verify(contacts).create(any(),eq("1"),argThat(c->c.phone()==null&&"微信昵称".equals(c.wechatHint())),any());}
    @Test void validatesPhoneAndKeepsFormattingSeparateFromVerification(){
        assertThat(StoreContactService.phone("138 0000 0000")).isEqualTo("13800000000");assertThat(StoreContactService.phone("0571-88888888")).isEqualTo("057188888888");
        for(String value:new String[]{"123","nickname","00000000000"})assertThatThrownBy(()->StoreContactService.phone(value)).isInstanceOf(RequestValidationException.class);
    }
    @Test void manualWecomIdentityIsRejected(){assertThatThrownBy(()->service.addMethod("1","2","MINE",new AddMethod(UUID.randomUUID().toString(),"WECOM","fake-id","手填"))).isInstanceOf(RequestValidationException.class);verifyNoInteractions(contacts);}
}
