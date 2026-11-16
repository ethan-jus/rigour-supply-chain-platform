package com.rigour.order.infrastructure.persistence.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JdbcOrderWorkbenchStoreTest {
    JdbcTemplate jdbc;
    JdbcOrderWorkbenchStore store;
    OrderDataScope scopes;
    Instant from = Instant.parse("2026-10-31T16:00:00Z"), until = Instant.parse("2026-11-30T16:00:00Z");
    @BeforeEach void setup() {
        jdbc = spy(new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1")));
        jdbc.execute("CREATE TABLE order_sales_order(id BIGINT, tenant_id VARCHAR, owner_employee_code VARCHAR, region_code VARCHAR, deleted INT, order_status_code VARCHAR, payment_status_code VARCHAR, outbound_status_code VARCHAR, order_date TIMESTAMP, payable_amount DECIMAL(24,6), unpaid_amount DECIMAL(24,6), customer_name_snapshot VARCHAR, order_no VARCHAR)");
        jdbc.execute("CREATE TABLE order_payment_record(id BIGINT,tenant_id VARCHAR,order_id BIGINT,payment_no VARCHAR,payment_time TIMESTAMP,paid_amount DECIMAL(24,6),payment_status_code VARCHAR,deleted INT)");
        jdbc.execute("ALTER TABLE order_sales_order ADD data_quality_status_code VARCHAR DEFAULT 'COMPLETE'");
        jdbc.execute("ALTER TABLE order_sales_order ADD source_system_code VARCHAR DEFAULT 'DHB'");
        jdbc.execute("CREATE TABLE order_attribution_snapshot(tenant_id VARCHAR,order_id BIGINT,employee_code VARCHAR,state VARCHAR)");
        jdbc.execute("CREATE TABLE order_refund_record(tenant_id VARCHAR,order_id BIGINT,refund_amount DECIMAL(24,6),refund_time TIMESTAMP,refund_status_code VARCHAR,deleted INT)");
        scopes = mock(OrderDataScope.class);
        when(scopes.predicate(anyString(), eq("o."), isNull())).thenReturn(new OrderDataScope.Predicate("o.region_code=?", List.of("allowed")));
        store = new JdbcOrderWorkbenchStore(jdbc, scopes);
        order(1,"tenant","self","allowed","SUBMITTED","2026-11-02T00:00:00Z",100,40);
        order(2,"tenant","self","allowed","COMPLETED","2026-10-02T00:00:00Z",200,60);
        order(3,"tenant","other","allowed","SUBMITTED","2026-11-02T00:00:00Z",999,999);
        order(4,"other","self","allowed","SUBMITTED","2026-11-02T00:00:00Z",999,999);
        order(5,"tenant","self","blocked","SUBMITTED","2026-11-02T00:00:00Z",999,999);
        order(6,"tenant","self","allowed","DRAFT","2026-11-02T00:00:00Z",999,999);
        order(7,"tenant","self","allowed","CANCELLED","2026-11-02T00:00:00Z",999,999);
    }
    void order(int id,String tenant,String employee,String region,String status,String time,int amount,int unpaid) {
        jdbc.update("INSERT INTO order_sales_order(id,tenant_id,owner_employee_code,region_code,deleted,order_status_code,payment_status_code,outbound_status_code,order_date,payable_amount,unpaid_amount,customer_name_snapshot,order_no) VALUES(?,?,?,?,0,?,'PARTIAL_PAID','PENDING',?,?,?,'门店',?)",id,tenant,employee,region,status,Timestamp.from(Instant.parse(time)),amount,unpaid,"SO-"+id);
    }
    void receipt(int id,int order,String time,int amount,String status) {
        jdbc.update("INSERT INTO order_payment_record VALUES(?,'tenant',?,?,?, ?,?,0)",id,order,"PAY-"+id,Timestamp.from(Instant.parse(time)),amount,status);
    }
    @Test void tradeUsesBusinessMonthAndExcludesDraftCancelledOtherOwnerTenantAndScope() {
        var page = store.details("tenant","self","order:read","orders",from,until,0,20);
        assertThat(page.total()).isEqualTo(1); assertThat(page.amount()).isEqualByComparingTo("100");
        assertThat(page.items()).extracting(r -> r.orderId()).containsExactly("1");
    }
    @Test void cumulativePendingAndOutboundIgnoreSelectedMonthAndTotalsIgnorePagination() {
        var page = store.details("tenant","self","order:read","unpaid",from,until,0,1);
        assertThat(page.total()).isEqualTo(2); assertThat(page.amount()).isEqualByComparingTo("100");
        assertThat(page.items()).hasSize(1);
        var next = store.details("tenant","self","order:read","unpaid",from,until,1,1);
        assertThat(next.items()).extracting(r -> r.orderId()).containsExactly("2");
        assertThat(store.details("tenant","self","order:read","outbound",from,until,0,20).total()).isEqualTo(2);
    }
    @Test void receiptUsesPaymentDateAndOrderOwnerIncludingOldOrdersButOnlyConfirmedReceipts() {
        receipt(1,2,"2026-10-31T16:00:00Z",30,"RECEIVED");
        receipt(2,1,"2026-11-30T15:59:59Z",40,"CHECKED");
        receipt(3,1,"2026-11-30T16:00:00Z",900,"RECEIVED");
        receipt(4,1,"2026-11-02T00:00:00Z",900,"CANCELLED");
        receipt(5,3,"2026-11-02T00:00:00Z",900,"RECEIVED");
        receipt(6,1,"2026-11-02T00:00:00Z",900,"PENDING");
        var page = store.details("tenant","self","order:payment:read","receipts",from,until,0,20);
        assertThat(page.total()).isEqualTo(2); assertThat(page.amount()).isEqualByComparingTo("70");
    }
    @Test void fullPaymentCountsWholeOrderOnceInSettlementMonthWithAllHistoryAndFrozenOwner() {
        receipt(1,2,"2026-10-10T00:00:00Z",150,"RECEIVED");
        receipt(2,2,"2026-10-31T16:00:00Z",50,"CHECKED");
        receipt(3,2,"2026-11-04T00:00:00Z",20,"RECEIVED");
        receipt(4,1,"2026-11-02T00:00:00Z",40,"RECEIVED");
        receipt(5,1,"2026-11-03T00:00:00Z",60,"CHECKED");
        // Later ownership changes do not transfer already attributed performance.
        jdbc.update("UPDATE order_sales_order SET owner_employee_code='other' WHERE id=2");
        jdbc.update("INSERT INTO order_attribution_snapshot VALUES('tenant',2,'self','FROZEN')");
        for (int id : List.of(3,4,5,6,7)) receipt(10+id,id,"2026-11-05T00:00:00Z",999,"RECEIVED");
        order(8,"tenant","self","allowed","SUBMITTED","2026-10-02T00:00:00Z",100,0);
        receipt(20,8,"2026-10-30T00:00:00Z",100,"RECEIVED");
        receipt(21,8,"2026-11-02T00:00:00Z",20,"RECEIVED");
        order(9,"tenant","self","allowed","SUBMITTED","2026-11-02T00:00:00Z",100,50);
        receipt(22,9,"2026-11-02T00:00:00Z",50,"RECEIVED");
        receipt(23,9,"2026-11-03T00:00:00Z",50,"CANCELLED");
        receipt(24,9,"2026-11-30T16:00:00Z",50,"RECEIVED");
        var page = store.details("tenant","self","order:payment:read","performance",from,until,0,1);
        assertThat(page.total()).isEqualTo(2); assertThat(page.amount()).isEqualByComparingTo("300");
        assertThat(page.items()).extracting(r -> r.orderId()).containsExactly("1");
        var next = store.details("tenant","self","order:payment:read","performance",from,until,1,1);
        assertThat(next.items()).extracting(r -> r.orderId()).containsExactly("2");
        assertThat(next.items().getFirst().amount()).isEqualByComparingTo("200");
    }
    @Test void refundsAndUnconfirmedAttributionCannotBecomeAConfirmedAchievementTotal() {
        receipt(1,1,"2026-11-02T00:00:00Z",100,"RECEIVED");
        jdbc.update("INSERT INTO order_refund_record VALUES('tenant',1,20,?,'CONFIRMED',0)",Timestamp.from(Instant.parse("2026-11-03T00:00:00Z")));
        var page = store.details("tenant","self","order:payment:read","performance",from,until,0,20);
        assertThat(page.amount()).isNull();
        assertThat(page.items().getFirst().status()).isEqualTo("PENDING_REVIEW");
        jdbc.update("DELETE FROM order_refund_record");
        jdbc.update("INSERT INTO order_attribution_snapshot VALUES('tenant',1,'self','PENDING')");
        assertThat(store.details("tenant","self","order:payment:read","performance",from,until,0,20).amount()).isNull();
    }
    @Test void refundBeforeFinalReceiptDelaysFullSettlementAndZeroValueOrdersDoNotCount() {
        receipt(1,1,"2026-11-02T00:00:00Z",50,"RECEIVED");
        jdbc.update("INSERT INTO order_refund_record VALUES('tenant',1,20,?,'CONFIRMED',0)",Timestamp.from(Instant.parse("2026-11-03T00:00:00Z")));
        receipt(2,1,"2026-11-04T00:00:00Z",50,"RECEIVED");
        order(8,"tenant","self","allowed","SUBMITTED","2026-11-02T00:00:00Z",0,0);
        receipt(3,8,"2026-11-04T00:00:00Z",10,"RECEIVED");
        assertThat(store.details("tenant","self","order:payment:read","performance",from,until,0,20).total()).isZero();
        receipt(4,1,"2026-11-05T00:00:00Z",20,"RECEIVED");
        var page = store.details("tenant","self","order:payment:read","performance",from,until,0,20);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().getFirst().occurredAt()).isEqualTo(Instant.parse("2026-11-05T00:00:00Z"));
    }

    @Test void targetAccessRequiresTheIamResolvedEmployeeEvenWithBroadOrderPermissions() {
        assertThatThrownBy(() -> store.requireSelf("self","order:payment:read"))
                .isInstanceOf(com.rigour.shared.context.AuthorizationDeniedException.class);
        var policy = mock(com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.class);
        when(policy.employeeCode()).thenReturn("self");
        when(scopes.policy("order:payment:read")).thenReturn(policy);
        store.requireSelf("self","order:payment:read");
        assertThatThrownBy(() -> store.requireSelf("other","order:payment:read"))
                .isInstanceOf(com.rigour.shared.context.AuthorizationDeniedException.class);
    }

    long cacheVersion = 1;
    void enableCache() {
        var payloads = new java.util.HashMap<String, byte[]>();
        var data = new com.rigour.shared.cache.CacheStore() {
            public java.util.Optional<byte[]> get(String tenant, String key) { return java.util.Optional.ofNullable(payloads.get(tenant+key)); }
            public void put(String tenant, String key, byte[] value, java.time.Duration ttl) {
                assertThat(ttl.toMillis()).isBetween(1L, 10000L); payloads.put(tenant+key,value);
            }
            public void evict(String tenant, String key) { payloads.remove(tenant+key); }
            public boolean tryLock(String tenant, String key, String token, java.time.Duration lease) { return true; }
            public void unlock(String tenant, String key, String token) {}
        };
        var versions = new com.rigour.shared.cache.CacheVersions() {
            public long current(String tenant) { return cacheVersion; }
            public void changed(String tenant) { cacheVersion++; }
            public boolean dirty(String tenant) { return false; }
        };
        org.springframework.test.util.ReflectionTestUtils.setField(store, "queryCache", new com.rigour.shared.cache.QueryCache(
                data, versions, () -> "current-authority", () -> false, null, "test", 100000, 4));
    }
    @Test void summaryMatchesDetailTotalsWithoutDiscardedRowsAndCachesByScopeDatesAndVersion() {
        enableCache();
        var summary = store.summary("tenant","self","order:read",from,until);
        assertThat(summary.tradeAmount()).isEqualByComparingTo("100");
        assertThat(summary.orderCount()).isEqualTo(1);
        assertThat(summary.pendingAmount()).isEqualByComparingTo("100");
        assertThat(summary.pendingCount()).isEqualTo(2);
        assertThat(summary.outboundCount()).isEqualTo(2);
        assertThat(summary.pendingPreview().orderId()).isEqualTo("1");
        clearInvocations(jdbc, scopes);
        assertThat(store.summary("tenant","self","order:read",from,until)).isEqualTo(summary);
        verifyNoInteractions(jdbc); verify(scopes).predicate("order:read","o.",null);
        order(8,"tenant","self","allowed","SUBMITTED","2026-11-03T00:00:00Z",50,20);
        cacheVersion++;
        assertThat(store.summary("tenant","self","order:read",from,until).tradeAmount()).isEqualByComparingTo("150");
        assertThat(store.summary("tenant","self","order:read",from.minusSeconds(86400*31),from).tradeAmount()).isEqualByComparingTo("200");
        when(scopes.predicate(anyString(),eq("o."),isNull())).thenReturn(new OrderDataScope.Predicate("1=0",List.of()));
        assertThat(store.summary("tenant","self","order:read",from,until).orderCount()).isZero();
        when(scopes.predicate(anyString(),eq("o."),isNull())).thenThrow(new com.rigour.shared.context.AuthorizationDeniedException("revoked"));
        assertThatThrownBy(() -> store.summary("tenant","self","order:read",from,until)).isInstanceOf(com.rigour.shared.context.AuthorizationDeniedException.class);
    }
    @Test void performanceAggregateExecutesOneCteAndKeepsUnknownAmountAcrossCacheSerialization() {
        enableCache(); receipt(1,1,"2026-11-02T00:00:00Z",100,"RECEIVED");
        clearInvocations(jdbc);
        var total = store.performanceTotals("tenant","self","order:payment:read",from,until);
        assertThat(total.total()).isEqualTo(1); assertThat(total.amount()).isEqualByComparingTo("100");
        var sqlCalls = mockingDetails(jdbc).getInvocations().stream().filter(i -> i.getMethod().getName().equals("queryForObject")).toList();
        assertThat(sqlCalls).hasSize(1);
        assertThat(mockingDetails(jdbc).getInvocations()).noneMatch(i -> i.getArguments().length>0 && i.getArguments()[0] instanceof String sql && sql.contains("SELECT * FROM eligible"));
        clearInvocations(jdbc);
        assertThat(store.performanceTotals("tenant","self","order:payment:read",from,until)).isEqualTo(total);
        verifyNoInteractions(jdbc);
        jdbc.update("INSERT INTO order_attribution_snapshot VALUES('tenant',1,'self','PENDING')"); cacheVersion++;
        assertThat(store.performanceTotals("tenant","self","order:payment:read",from,until).amount()).isNull();
        assertThat(store.performanceTotals("tenant","self","order:payment:read",from,until).amount()).isNull();
        assertThat(store.performanceTotals("other","self","order:payment:read",from,until).total()).isZero();
    }

}
