package com.rigour.sales.application.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.VisitPolicyCommand;
import com.rigour.sales.api.v1.model.SalesVisitPolicyModels.CaptureRules;
import com.rigour.sales.application.port.out.SalesWorkAdminRepository;
import com.rigour.shared.audit.AuditSink;
import com.rigour.shared.context.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
class SalesVisitPolicyPublicationTest {
    final UUID tenant=UUID.randomUUID(),user=UUID.randomUUID(),policy=UUID.randomUUID(),version=UUID.randomUUID();
    final SalesWorkAdminRepository repository=mock(SalesWorkAdminRepository.class);
    final SalesWorkAdminService service=new SalesWorkAdminService(repository,mock(AuditSink.class),Clock.systemUTC());
    final CaptureRules rules=new CaptureRules(true,100,true,false,false);
    void caller(Set<String> permissions) throws Exception {
        var setter=AuthorizationContext.class.getDeclaredMethod("set",CallerIdentity.class);setter.setAccessible(true);
        setter.invoke(null,new CallerIdentity("TENANT",user,tenant,user,null,UUID.randomUUID(),1,1,1,Set.of("SALES"),permissions));
    }
    @BeforeEach void setup() throws Exception {
        caller(Set.of("sales:policy:read","sales:policy:write","sales:policy:publish"));
        when(repository.ensureVisitPolicy(any(),anyString(),anyString(),any())).thenReturn(new SalesWorkAdminRepository.PolicyIdentity(policy,4));
        when(repository.insertVisitPolicyVersion(any(),any(),anyInt(),anyBoolean(),anyBoolean(),anyBoolean(),anyInt(),anyInt(),anyInt(),anyBoolean(),anyInt(),anyInt(),anyBoolean(),anyBoolean(),anyBoolean(),any(),any(),any())).thenReturn(version);
    }
    @AfterEach void clear() throws Exception { var method=AuthorizationContext.class.getDeclaredMethod("clear");method.setAccessible(true);method.invoke(null); }
    VisitPolicyCommand command(int expected,CaptureRules capture) { return new VisitPolicyCommand("APP","销售作业",true,false,500,10,1,true,600,30,false,false,false,null,"ALL",null,true,capture,expected); }
    @Test void publishedVersionPersistsRulesAndScopeWithTenantAndActor() {
        var result=service.upsertVisitPolicy(command(3,rules));
        assertThat(result.versionNo()).isEqualTo(4);
        verify(repository).saveVisitCaptureRules(tenant,version,rules);
        verify(repository).insertPolicyScope(eq(tenant),eq("VISIT"),eq(version),eq("ALL"),isNull(),eq(user),any());
    }
    @Test void zeroCumulativeRecordingThresholdCanBePublishedWithoutDisablingRecording() {
        var command=new VisitPolicyCommand("APP","销售作业",true,false,500,0,1,true,0,30,false,false,false,null,"ALL",null,true,new CaptureRules(false,100,true,false,false,true),3);
        assertThat(service.upsertVisitPolicy(command).versionNo()).isEqualTo(4);
        verify(repository).saveVisitCaptureRules(tenant,version,command.captureRules());
    }
    @Test void staleEditorCannotPublishAnotherVersion() {
        assertThatThrownBy(()->service.upsertVisitPolicy(command(2,rules))).hasMessageContaining("政策已更新");
        verify(repository,never()).saveVisitCaptureRules(any(),any(),any());
    }
    @Test void salesOperatorCannotPublishConfiguration() throws Exception {
        caller(Set.of("sales:context:read","sales:visit:own:write"));
        assertThatThrownBy(()->service.upsertVisitPolicy(command(3,rules))).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(repository);
    }
    @Test void invalidAccuracyFailsBeforePersistence() {
        assertThatThrownBy(()->service.upsertVisitPolicy(command(3,new CaptureRules(true,0,true,false,false)))).hasMessageContaining("定位精度");
        verifyNoInteractions(repository);
    }
}
