package com.rigour.analytics.infrastructure.persistence.repository;
import com.rigour.analytics.api.v1.model.TargetSettingsModels.*;
import com.rigour.analytics.application.port.out.TargetSettingsStore;
import com.rigour.analytics.application.service.*;
import com.rigour.shared.context.*;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class TargetSettingsTransactionTest {
    private JdbcTemplate jdbc;
    private SingleConnectionDataSource ds;
    private JdbcTargetSettingsStore store;
    private TargetSettingsService service;
    private final UUID tenant=UUID.randomUUID(),user=UUID.randomUUID();
    private final Subject subject=new Subject("CITY","BJ","北京","BJ","北京",null,null,true);
    @BeforeEach void setup() throws Exception {
        ds=new SingleConnectionDataSource("jdbc:h2:mem:targets_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","",true);
        jdbc=new JdbcTemplate(ds);
        String v9=Files.readString(Path.of("src/main/resources/db/migration/V9__bi_operating_dashboard_business_metrics.sql"));
        execute(v9.substring(v9.indexOf("CREATE TABLE bi_business_target"),v9.indexOf("CREATE TABLE bi_inventory_operation_fact")));
        execute(Files.readString(Path.of("src/main/resources/db/migration/V10__bi_operating_actions.sql")));
        execute(Files.readString(Path.of("src/main/resources/db/migration/V26__bi_monthly_target_defaults.sql")));
        store=spy(new JdbcTargetSettingsStore(jdbc));
        doReturn(List.of(subject)).when(store).subjects(tenant.toString());
        doReturn(true).when(store).permitted(anyString(),anyString(),anyString(),anyString());
        var raw=new TargetSettingsService(store,mock(BiDataScopeService.class),Clock.fixed(Instant.parse("2026-10-09T08:00:00Z"),ZoneOffset.UTC));
        var proxy=new ProxyFactory(raw);proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));
        service=(TargetSettingsService)proxy.getProxy();
        TestAuthorizationContext.set(new CallerIdentity("TENANT",user,tenant,user,null,UUID.randomUUID(),1,1,1,Set.of(),Set.of(TargetSettingsService.READ,TargetSettingsService.WRITE,TargetSettingsService.DEFAULTS_WRITE)));
    }
    void execute(String sql) { jdbc.execute(sql.replaceAll("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_[a-z0-9_]+", "")); }
    @AfterEach void close() { TestAuthorizationContext.clear(); jdbc.execute("SHUTDOWN");ds.destroy(); }
    Change c(String metric,String value,int version) { return new Change("CITY","BJ",metric,value==null?null:new BigDecimal(value),version); }
    void save(Change... changes) { service.save(new Batch("2026-10",List.of(changes),"测试调整")); }
    @Test void persistenceHistoryResetAndZeroKeepTheirDistinctMeaning() {
        save(c("SALES_AMOUNT","120000",0),c("RECEIPT_AMOUNT","0",0));
        assertThat(service.settings("2026-10").overrides()).hasSize(2);
        save(c("SALES_AMOUNT",null,1));
        var reset=service.settings("2026-10").overrides().stream().filter(o->o.metric().equals("SALES_AMOUNT")).findFirst().orElseThrow();
        assertThat(reset.deleted()).isTrue();assertThat(reset.revision()).isEqualTo(2);
        assertThatThrownBy(()->save(c("SALES_AMOUNT","8",1))).isInstanceOf(RuntimeException.class);
        save(c("SALES_AMOUNT","8",2));
        assertThat(service.history("2026-10","CITY","BJ")).hasSize(4);
        assertThat(store.overrides(UUID.randomUUID().toString(),"2026-10")).isEmpty();
        assertThat(store.history(UUID.randomUUID().toString(),"2026-10","CITY","BJ")).isEmpty();
    }
    @Test void lateRevisionConflictRollsBackEarlierItemsAndAudit() {
        save(c("RECEIPT_AMOUNT","500",0));
        assertThatThrownBy(()->save(c("SALES_AMOUNT","999",0),c("RECEIPT_AMOUNT","600",0))).isInstanceOf(RuntimeException.class);
        assertThat(service.settings("2026-10").overrides()).singleElement().extracting(TargetOverride::value).isEqualTo(new BigDecimal("500.000000"));
        assertThat(service.history("2026-10","CITY","BJ")).hasSize(1);
    }
    @Test void auditFailureRollsBackEntireBatch() {
        jdbc.execute("ALTER TABLE bi_business_target_event ADD CONSTRAINT reject_event CHECK(target_value<>13)");
        assertThatThrownBy(()->save(c("SALES_AMOUNT","10",0),c("RECEIPT_AMOUNT","13",0))).isInstanceOf(RuntimeException.class);
        assertThat(service.settings("2026-10").overrides()).isEmpty();
        assertThat(service.history("2026-10","CITY","BJ")).isEmpty();
    }
    @Test void scheduledDefaultsAreTenantScopedVersionedAndAudited() {
        service.saveDefaults(new DefaultsBatch("2026-11","CITY",List.of(new DefaultChange("SALES_AMOUNT",new BigDecimal("200000"),0)),"新标准"));
        assertThat(store.defaults(tenant.toString())).singleElement().satisfies(d->{assertThat(d.effectiveMonth()).isEqualTo("2026-11");assertThat(d.revision()).isEqualTo(1);});
        assertThat(store.defaults(UUID.randomUUID().toString())).isEmpty();
        assertThat(service.history("2026-11","CITY","DEFAULT")).hasSize(1);
        assertThatThrownBy(()->service.saveDefaults(new DefaultsBatch("2026-11","CITY",List.of(new DefaultChange("SALES_AMOUNT",BigDecimal.ONE,0)),"过期"))).isInstanceOf(RuntimeException.class);
    }
}
