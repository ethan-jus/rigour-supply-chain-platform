package com.rigour.hr.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.*;
import com.rigour.hr.application.service.PayrollPaymentAttachmentService;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercise MySQL's default RR snapshot/current-read distinction with two real transactions. */
@Testcontainers(disabledWithoutDocker=true)
class PayrollPaymentAttachmentMySqlTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4").withDatabaseName("payroll_evidence")
            .withUsername("test").withPassword("isolated_test_password");
    JdbcTemplate jdbc;
    PayrollPaymentAttachmentService service;
    final UUID tenant=UUID.randomUUID(),user=UUID.randomUUID();
    final String payrollId=UUID.randomUUID().toString(), paymentId=UUID.randomUUID().toString();
    final AtomicInteger uploaded=new AtomicInteger();
    final byte[] pdf="%PDF-1.7\npaid evidence".getBytes(StandardCharsets.US_ASCII);
    final CountDownLatch snapshots=new CountDownLatch(2);
    final AtomicInteger authorizationCalls=new AtomicInteger();

    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS hr_payroll_payment_attachment");
        jdbc.execute("DROP TABLE IF EXISTS hr_payroll_payment");
        jdbc.execute("DROP TABLE IF EXISTS hr_payroll");
        jdbc.execute("CREATE TABLE hr_payroll(id VARCHAR(36) NOT NULL,tenant_id VARCHAR(64) NOT NULL,revision INT NOT NULL,status_code VARCHAR(16) NOT NULL,PRIMARY KEY(id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
        String workflow=new ClassPathResource("db/migration/V13__hr_payroll_workflow.sql").getContentAsString(StandardCharsets.UTF_8);
        jdbc.execute(workflow.substring(workflow.indexOf("CREATE TABLE hr_payroll_payment"),workflow.indexOf("CREATE TABLE hr_payroll_correction")).strip());
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V26__payroll_payment_attachments.sql")).execute(ds);
        jdbc.update("INSERT INTO hr_payroll VALUES(?,?,4,'PAID')",payrollId,tenant.toString());
        jdbc.update("INSERT INTO hr_payroll_payment(id,tenant_id,payroll_id,request_id,paid_date,amount,reference_no,reason,created_by) VALUES(?,?,?,?,CURRENT_DATE,5000,'BANK','已登记',?)",paymentId,tenant.toString(),payrollId,UUID.randomUUID().toString(),user.toString());
        var payroll=mock(HrPayrollStore.class);
        var detail=mock(PayrollDetailView.class);
        when(detail.payments()).thenReturn(List.of(new PayrollPaymentView(paymentId,"request",LocalDate.now(),new java.math.BigDecimal("5000"),"BANK","已登记",Instant.EPOCH)));
        // Deliberately retain a stale authorized snapshot; the locked current row must determine concurrency.
        when(detail.revision()).thenReturn(4);
        when(detail.statusCode()).thenReturn("PAID");
        when(payroll.detail(anyString(),anyString(),anyString())).thenAnswer(invocation -> {
            jdbc.queryForObject("SELECT COUNT(*) FROM hr_payroll_payment_attachment WHERE tenant_id=? AND payroll_id=?",Integer.class,tenant.toString(),payrollId);
            if(authorizationCalls.incrementAndGet()<=2) {
                snapshots.countDown();
                if(!snapshots.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("two initial snapshots were not established");
            }
            return detail;
        });
        var store=new JdbcPayrollPaymentAttachmentStore(jdbc,mock(HrPayrollPersistenceSupport.class));
        var storage=mock(PayrollPaymentAttachmentStorage.class);
        when(storage.upload(anyString(),anyString(),anyString(),anyString(),any())).thenAnswer(invocation -> { uploaded.incrementAndGet(); return "private/"+UUID.randomUUID(); });
        var names=mock(HrAuditActorNameResolver.class);
        when(names.resolve(anyString(),anyString())).thenReturn("李四");
        var factory=new ProxyFactory(new PayrollPaymentAttachmentService(payroll,store,storage,names));
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));
        service=(PayrollPaymentAttachmentService)factory.getProxy();
    }
    void caller() { TestAuthorizationContext.set(new CallerIdentity("TENANT",user,tenant,user,null,UUID.randomUUID(),1,1,1,Set.of(),Set.of("hr:payroll:paid"))); }
    @AfterEach void close() { TestAuthorizationContext.clear(); }

    @Test void repeatableReadConcurrentLastSlotUsesCurrentAttachmentRows() throws Exception {
        assertThat(jdbc.queryForObject("SELECT @@transaction_isolation",String.class)).isEqualTo("REPEATABLE-READ");
        for(int i=0;i<19;i++) jdbc.update("INSERT INTO hr_payroll_payment_attachment(id,tenant_id,payroll_id,payment_id,file_key,file_name,content_type,file_size,created_by) VALUES(?,?,?,?,?,'已有回单.pdf','application/pdf',20,?)",
                UUID.randomUUID().toString(),tenant.toString(),payrollId,paymentId,"private/old-"+i,user.toString());
        var executor=Executors.newFixedThreadPool(2);
        try {
            var results=new ArrayList<Future<Boolean>>();
            for(int i=0;i<2;i++) {
                final int index=i;
                results.add(executor.submit(() -> { caller(); try {
                    service.upload(payrollId,paymentId,4,"并发回单"+index+".pdf","application/pdf",pdf); return true;
                } catch(BusinessException failure) { assertThat(failure.getMessage()).contains("最多20"); return false; }
                finally { TestAuthorizationContext.clear(); } }));
            }
            int success=0;
            for(var result:results) if(result.get(20,TimeUnit.SECONDS)) success++;
            assertThat(success).isEqualTo(1);
            assertThat(uploaded).hasValue(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hr_payroll_payment_attachment",Integer.class)).isEqualTo(20);
        } finally { executor.shutdownNow(); }
    }

    @Test void currentLockedRevisionOverridesTheOlderAuthorizationSnapshot() {
        snapshots.countDown(); snapshots.countDown();
        jdbc.update("UPDATE hr_payroll SET revision=5 WHERE id=?",payrollId);
        caller();
        assertThatThrownBy(() -> service.upload(payrollId,paymentId,4,"旧版本.pdf","application/pdf",pdf))
                .isInstanceOfSatisfying(BusinessException.class,failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(uploaded).hasValue(0);
    }
}
