package com.rigour.merchant.application.service;
import static org.assertj.core.api.Assertions.*;import static org.mockito.Mockito.*;
import com.rigour.merchant.application.port.out.*;import com.rigour.merchant.api.v1.model.StoreVisitFieldworkModels.*;
import com.rigour.merchant.api.v1.model.StoreContactModels.Contact;
import com.rigour.settings.api.v1.model.*;
import java.util.*;import org.junit.jupiter.api.*;
class StoreVisitFieldworkServiceTest {
    StorePoolService pools=mock(StorePoolService.class);StoreVisitStore visits=mock(StoreVisitStore.class);StoreContactStore contacts=mock(StoreContactStore.class);StoreVisitFieldworkStore store=mock(StoreVisitFieldworkStore.class);StoreReferenceClient reference=mock(StoreReferenceClient.class);
    StoreVisitFieldworkService service=new StoreVisitFieldworkService(pools,visits,contacts,store,reference);
    @BeforeEach void setup(){
        var scope=mock(StoreScope.class);var access=mock(com.rigour.tenant.iam.api.v1.model.StoreAccessView.class);when(scope.access()).thenReturn(access);when(access.tenantId()).thenReturn("tenant");when(access.userId()).thenReturn("user");when(access.employeeId()).thenReturn("employee");when(pools.scopeForStore(anyString(),eq("1"))).thenReturn(scope);
        var options=Map.of("CRM_STORE_BUSINESS_STATUS",List.of("OPEN","TEMP_CLOSED","UNKNOWN"),"STORE_BUSINESS_TYPE",List.of("TYPE"),"VISIT_CONTACT_OUTCOME",List.of("CONTACTED","OTHER_NO_CONTACT"),"VISIT_NO_CONTACT_REASON",List.of("OTHER","BUSY"),"BUSINESS_LINE",List.of("LINE"),"CUSTOMER_INTENTION_LEVEL",List.of("HIGH","NONE"),"CUSTOMER_COOP_STATUS",List.of("COOPERATED"),"VISIT_NEXT_ACTION",List.of("CONTACT"));
        when(reference.dictionaries(eq("tenant"),any())).thenReturn(options.entrySet().stream().map(e->new EffectiveDictView(new DictView(1L,e.getKey(),e.getKey(),"CRM",null,1),e.getValue().stream().map(code->new DictItemView(1L,e.getKey(),1,null,code,code,null,1,1)).toList())).toList());
        when(contacts.list(any(),eq("1"))).thenReturn(List.of(new Contact("3","接待",1,"店长",false,"ACTIVE",1,List.of(),List.of())));
    }
    Draft draft(String status,String reason,String outcome,List<String> people,boolean none,List<Progress> progress,String noContact,String detail){return new Draft(1,status,reason,"TYPE",outcome,people,none,progress,noContact,detail,null,null);}
    @Test void contactedRequiresReceptionAndProgressWithSpecificMessages(){
        assertThatThrownBy(()->service.validate("1","2",draft("OPEN",null,"CONTACTED",List.of(),true,List.of(),null,null))).hasMessageContaining("接待人");
        assertThatThrownBy(()->service.validate("1","2",draft("OPEN",null,"CONTACTED",List.of("3"),false,List.of(),null,null))).hasMessageContaining("业务进展");verifyNoInteractions(store);
    }
    @Test void closedAndNoContactOtherRequireTheirOwnEvidence(){
        assertThatThrownBy(()->service.validate("1","2",draft("TEMP_CLOSED",null,"OTHER_NO_CONTACT",List.of(),false,List.of(),"BUSY",null))).hasMessageContaining("停业依据");
        assertThatThrownBy(()->service.validate("1","2",draft("OPEN",null,"OTHER_NO_CONTACT",List.of(),false,List.of(),"OTHER",null))).hasMessageContaining("未接触原因");
    }
    @Test void cooperationIsSeparateFromIntentionAndDisabledOptionsAreRejected(){
        var d=draft("OPEN",null,"CONTACTED",List.of("3"),false,List.of(new Progress("LINE",null,"COOPERATED")),null,null);
        assertThat(service.validate("1","2",d).draft().businesses().getFirst().intentionLevel()).isNull();
        assertThatThrownBy(()->service.validate("1","2",draft("OPEN",null,"CONTACTED",List.of("3"),false,List.of(new Progress("DISABLED","HIGH",null)),null,null))).hasMessageContaining("有效业务");
    }
    @Test void noContactDoesNotDemandReceptionOrIntention(){assertThat(service.validate("1","2",draft("OPEN",null,"OTHER_NO_CONTACT",List.of(),false,List.of(),"BUSY",null)).contacts()).isEmpty();}
    @Test void statusObservationDoesNotBypassExistingStatusPermission(){
        when(pools.scopeForStore("crm:store:status-write","1")).thenThrow(new com.rigour.shared.context.AuthorizationDeniedException("crm:store:status-write"));
        assertThatThrownBy(()->service.validate("1","2",draft("OPEN",null,"OTHER_NO_CONTACT",List.of(),false,List.of(),"BUSY",null))).isInstanceOf(com.rigour.shared.context.AuthorizationDeniedException.class);
        verifyNoInteractions(store);
    }
}
