package com.rigour.hr.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.*;
import com.rigour.hr.application.service.PayrollPaymentAttachmentService;
import com.rigour.shared.context.*;
import com.rigour.shared.core.exception.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real JDBC/MyBatis transactions in isolation; MySQL migration is verified separately. */
class PayrollPaymentAttachmentPersistenceTest {
    final PayrollPersistenceWorkflowTest db = new PayrollPersistenceWorkflowTest();
    final UUID tenant=UUID.randomUUID(), user=UUID.randomUUID();
    final byte[] pdf="%PDF-1.7\nregistered payment evidence".getBytes(StandardCharsets.US_ASCII);
    final Map<String,byte[]> objects=new ConcurrentHashMap<>();
    final AtomicInteger uploads=new AtomicInteger();
    PayrollDetailView paid;
    String paymentId;
    JdbcPayrollPaymentAttachmentStore store;
    PayrollPaymentAttachmentService service;
    final PayrollPaymentAttachmentStorage storage=new PayrollPaymentAttachmentStorage() {
        public String upload(String t,String payroll,String payment,String type,byte[] bytes) {
            String key="private/"+UUID.randomUUID(); uploads.incrementAndGet(); objects.put(key,bytes.clone()); return key;
        }
        public byte[] read(String key) { return objects.get(key); }
        public String signedDownload(String key, String name, String type, java.time.Instant expiresAt) {
            return "https://example.invalid/private-receipt";
        }
        public void discard(String key) { objects.remove(key); }
    };

    @BeforeEach void setup() throws Exception {
        db.setup();
        db.schemaConnection.createStatement().execute(new ClassPathResource("db/migration/V26__payroll_payment_attachments.sql")
                .getContentAsString(StandardCharsets.UTF_8)
                .replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci", ""));
        caller();
        doReturn(List.of(db.employee())).when(db.references).employees(any());
        var calculated=db.payroll.saveCalculation(tenant.toString(),"E1","2026-09",db.employee(),db.calculation("ORIGINAL"),user.toString());
        var confirmed=db.payroll.transition(tenant.toString(),calculated.id(),new PayrollActionCommand(calculated.revision(),"确认"),"CONFIRMED",user.toString());
        paid=db.payroll.pay(tenant.toString(),confirmed.id(),new PayrollPaymentCommand(UUID.randomUUID().toString(),confirmed.revision(),
                LocalDate.of(2026,10,1),new BigDecimal("3040"),"BANK-ORIGINAL","登记实际已付，不执行转账"),user.toString());
        paymentId=paid.payments().getFirst().id();
        store=spy(new JdbcPayrollPaymentAttachmentStore(db.jdbc,db.support));
        db.payroll.setPaymentAttachments(store);
        var names=mock(HrAuditActorNameResolver.class);
        when(names.resolve(tenant.toString(),user.toString())).thenReturn("李四");
        var factory=new ProxyFactory(new PayrollPaymentAttachmentService(db.payroll,store,storage,names));
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(db.dataSource),new AnnotationTransactionAttributeSource()));
        service=(PayrollPaymentAttachmentService)factory.getProxy();
    }
    void caller() { TestAuthorizationContext.set(new CallerIdentity("TENANT",user,tenant,user,null,UUID.randomUUID(),1,1,1,Set.of(),Set.of("hr:payroll:read","hr:payroll:paid"))); }
    @AfterEach void close() throws Exception { db.closeSchemaConnection(); }
    PayrollPaymentAttachmentView upload(String name) { return service.upload(paid.id(),paymentId,paid.revision(),name,"application/pdf",pdf); }

    @Test void savedEvidenceIsReturnedByDetailAndExportWithoutChangingPayrollOrAnalyticsVersion() {
        Long guard=db.jdbc.queryForObject("SELECT version FROM hr_payroll_guard WHERE tenant_id=?",Long.class,tenant.toString());
        var file=upload("银行回单.pdf");
        assertThat(file.createdTime().getNano() % 1000).isZero();
        assertThat(service.upload(paid.id(),paymentId,1,"银行回单.pdf","application/pdf",pdf)).isEqualTo(file);
        var detail=db.payroll.detail(tenant.toString(),paid.id(),"hr:payroll:read");
        assertThat(detail.payments().getFirst().attachments()).containsExactly(file);
        assertThat(detail.revision()).isEqualTo(paid.revision());
        assertThat(detail.calculation()).isEqualTo(paid.calculation());
        assertThat(detail.statusCode()).isEqualTo("PAID");
        assertThat(db.jdbc.queryForObject("SELECT version FROM hr_payroll_guard WHERE tenant_id=?",Long.class,tenant.toString())).isEqualTo(guard);
        assertThat(db.payroll.export(tenant.toString(),new PayrollQuery(0,100,"2026-09",null,null,null),"hr:payroll:read"))
                .singleElement().satisfies(row -> assertThat(row.payments().getFirst().attachments()).containsExactly(file));
        assertThat(service.content(paid.id(),paymentId,file.id()).bytes()).containsExactly(pdf);
        assertThat(file.createdBy()).isEqualTo(user.toString());
        assertThat(file.createdByName()).isEqualTo("李四");
        assertThat(uploads).hasValue(1);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM hr_payroll_audit WHERE tenant_id=? AND object_id=? AND action_code='PAYMENT_ATTACHMENT_ADD'",Integer.class,tenant.toString(),paid.id())).isEqualTo(1);
    }

    @Test void databaseFailureRollsBackMetadataAndAuditAndRemovesUploadedPrivateObject() {
        doAnswer(invocation -> { invocation.callRealMethod(); throw new IllegalStateException("simulated database failure after insert"); })
                .when(store).add(anyString(),anyString(),anyString(),any(),anyString());
        assertThatThrownBy(() -> upload("回滚回单.pdf")).isInstanceOf(IllegalStateException.class);
        assertThat(objects).isEmpty();
        assertThat(service.list(paid.id(),paymentId)).isEmpty();
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM hr_payroll_audit WHERE action_code='PAYMENT_ATTACHMENT_ADD'",Integer.class)).isZero();
        assertThat(db.payroll.detail(tenant.toString(),paid.id(),"hr:payroll:read").revision()).isEqualTo(paid.revision());
    }

    @Test void tenantAndParentPaymentAreBothEnforcedByLookupAndCompositeForeignKey() {
        var file=upload("归属回单.pdf");
        assertThatThrownBy(() -> store.file(UUID.randomUUID().toString(),paid.id(),paymentId,file.id())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> store.file(tenant.toString(),paid.id(),UUID.randomUUID().toString(),file.id())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> db.jdbc.update("INSERT INTO hr_payroll_payment_attachment SELECT ?,tenant_id,payroll_id,?,file_key,file_name,content_type,file_size,created_by,created_by_name,created_time FROM hr_payroll_payment_attachment WHERE id=?",
                UUID.randomUUID().toString(),UUID.randomUUID().toString(),file.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void concurrentLastSlotCannotExceedTwentyFilesAndOnlyOneObjectIsWritten() throws Exception {
        for(int i=0;i<19;i++) upload("回单"+i+".pdf");
        var start=new CountDownLatch(1);
        var executor=Executors.newFixedThreadPool(2);
        try {
            var results=new ArrayList<Future<Boolean>>();
            for(int i=0;i<2;i++) {
                final int index=i;
                results.add(executor.submit(() -> { caller(); try { start.await(); upload("并发回单"+index+".pdf"); return true; }
                    catch(BusinessException failure) { assertThat(failure.getMessage()).contains("最多20"); return false; }
                    finally { TestAuthorizationContext.clear(); } }));
            }
            start.countDown();
            int successful=0;
            for(var result:results) if(result.get(15,TimeUnit.SECONDS)) successful++;
            assertThat(successful).isEqualTo(1);
            assertThat(service.list(paid.id(),paymentId)).hasSize(20);
            assertThat(objects).hasSize(20);
            assertThat(uploads).hasValue(20);
        } finally { executor.shutdownNow(); }
    }
}
