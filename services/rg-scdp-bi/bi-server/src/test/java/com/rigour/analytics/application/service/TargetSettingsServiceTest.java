package com.rigour.analytics.application.service;
import com.rigour.analytics.api.v1.model.TargetSettingsModels.*;
import com.rigour.analytics.application.port.out.TargetSettingsStore;
import com.rigour.shared.context.*;
import com.rigour.shared.core.exception.BusinessException;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class TargetSettingsServiceTest {
    private final UUID tenant=UUID.randomUUID(),user=UUID.randomUUID();
    private final TargetSettingsStore store=mock(TargetSettingsStore.class);
    private final BiDataScopeService scope=mock(BiDataScopeService.class);
    private final TargetSettingsService service=new TargetSettingsService(store,scope,Clock.fixed(Instant.parse("2026-10-09T08:00:00Z"),ZoneOffset.UTC));
    private final Subject city=new Subject("CITY","BJ","北京","BJ","北京",null,null,false);
    private final Subject sales=new Subject("SALES_OWNER","E1","销售","BJ","北京","销售部","ACTIVE",false);
    @BeforeEach void setup() {
        authorize(Set.of(TargetSettingsService.READ,TargetSettingsService.WRITE,TargetSettingsService.DEFAULTS_WRITE));
        when(store.subjects(tenant.toString())).thenReturn(List.of(city,sales));
        when(store.permitted(anyString(),anyString(),anyString(),anyString())).thenReturn(true);
        when(store.defaults(any())).thenReturn(List.of()); when(store.overrides(any(),any())).thenReturn(List.of());
    }
    @AfterEach void clear() { TestAuthorizationContext.clear(); }
    void authorize(Set<String> permissions) { TestAuthorizationContext.set(new CallerIdentity("TENANT",user,tenant,user,null,UUID.randomUUID(),1,1,1,Set.of(),permissions)); }
    Change change(String type,String code,String metric,String value) { return new Change(type,code,metric,value==null?null:new BigDecimal(value),0); }
    @Test void readDoesNotImplyWriteAndScopedSubjectsHideOverrides() {
        authorize(Set.of(TargetSettingsService.READ));
        when(store.permitted(tenant.toString(),"CITY","BJ",TargetSettingsService.READ)).thenReturn(false);
        var result=service.settings("2026-10");
        assertThat(result.subjects()).singleElement().satisfies(s -> {assertThat(s.code()).isEqualTo("E1");assertThat(s.writable()).isFalse();});
        assertThatThrownBy(()->service.save(new Batch("2026-10",List.of(change("CITY","BJ","SALES_AMOUNT","1")),"原因"))).isInstanceOf(AuthorizationDeniedException.class);
        verify(store,never()).save(any(),any(),any(),any(),any(),any(),any());
    }
    @Test void verifiesEntireBatchBeforeFirstWrite() {
        when(store.permitted(tenant.toString(),"SALES_OWNER","E1",TargetSettingsService.WRITE)).thenReturn(false);
        assertThatThrownBy(()->service.save(new Batch("2026-10",List.of(change("CITY","BJ","SALES_AMOUNT","1"),change("SALES_OWNER","E1","RECEIPT_AMOUNT","2")),"原因"))).isInstanceOf(AuthorizationDeniedException.class);
        verify(store,never()).save(any(),any(),any(),any(),any(),any(),any());
    }
    @Test void acceptsNewZeroSalespersonAndExplicitZeroAndReset() {
        service.save(new Batch("2026-10",List.of(change("SALES_OWNER","E1","SALES_AMOUNT","0"),change("SALES_OWNER","E1","RECEIPT_AMOUNT",null)),"试用期目标"));
        verify(store,times(2)).save(eq(tenant.toString()),eq(user.toString()),eq("2026-10"),eq(sales),any(),eq("试用期目标"),any());
    }
    @Test void rejectsFractionsNegativeMissingReasonAndDuplicateKeys() {
        for (var c:List.of(change("CITY","BJ","NEW_CUSTOMER","1.5"),change("CITY","BJ","SALES_AMOUNT","-1"),change("CITY","BJ","SALES_AMOUNT","1.001")))
            assertThatThrownBy(()->service.save(new Batch("2026-10",List.of(c),"原因"))).isInstanceOf(BusinessException.class);
        var c=change("CITY","BJ","SALES_AMOUNT","10");
        assertThatThrownBy(()->service.save(new Batch("2026-10",List.of(c),""))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.save(new Batch("2026-10",List.of(c,c),"原因"))).isInstanceOf(BusinessException.class);
    }
    @Test void futureDefaultDoesNotRewriteCurrentOrPastAndNeedsGlobalActionScope() {
        for(var month:List.of("2026-09","2026-10")) assertThatThrownBy(()->service.saveDefaults(new DefaultsBatch(month,"CITY",List.of(new DefaultChange("SALES_AMOUNT",BigDecimal.TEN,0)),"新标准"))).isInstanceOf(BusinessException.class);
        service.saveDefaults(new DefaultsBatch("2026-11","CITY",List.of(new DefaultChange("SALES_AMOUNT",BigDecimal.TEN,0)),"新标准"));
        verify(scope,atLeastOnce()).requireGlobalGovernance();
        verify(scope,atLeastOnce()).requireObjectActionScope(null,null,TargetSettingsService.DEFAULTS_WRITE);
        verify(store).saveDefault(eq(tenant.toString()),eq(user.toString()),eq("2026-11"),eq("CITY"),any(),eq("新标准"),any());
    }
    @Test void historyChecksReadScopeAndUnknownObject() {
        when(store.permitted(tenant.toString(),"CITY","BJ",TargetSettingsService.READ)).thenReturn(false);
        assertThatThrownBy(()->service.history("2026-10","CITY","BJ")).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(()->service.history("2026-10","CITY","UNKNOWN")).isInstanceOf(BusinessException.class);
        verify(store,never()).history(any(),any(),any(),any());
    }
}
