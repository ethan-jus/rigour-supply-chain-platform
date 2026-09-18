package com.rigour.tenant.iam.application.service.identity;
import com.rigour.tenant.iam.application.port.out.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class AuditActorServiceTest {
 @Test void resolvesOnlyCurrentTenantAndReturnsNamesWithoutIdentityDetails(){
  var reader=mock(AuditActorReader.class);var hr=mock(AppEmployeeClient.class);var service=new AuditActorService(reader,hr);
  UUID tenant=UUID.randomUUID(),id=UUID.randomUUID();
  var user=new CurrentUser(id,tenant,"测试企业","TENANT","admin","管理员",Set.of(),Set.of());
  var refs=List.of(id.toString(),"other");
  when(reader.actors(tenant,refs)).thenReturn(List.of(new AuditActorReader.ActorName(id.toString(),null,"管理员")));
  assertThat(service.resolve(user,refs)).containsExactlyEntriesOf(Map.of(id.toString(),"管理员"));
  verify(reader).actors(tenant,refs);verifyNoInteractions(hr);
  assertThatThrownBy(()->service.resolve(new CurrentUser(id,null,null,"PLATFORM","admin","管理员",Set.of(),Set.of()),refs)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  assertThatThrownBy(()->service.resolve(user,Collections.nCopies(101,"a"))).isInstanceOf(IllegalArgumentException.class);
 }
}
