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
        jdbc.execute("CREATE TABLE order_payment_record(id BIGINT,tenant_id VARCHAR,order_id BIGINT,payment_no VARCHAR,payment_time TIMESTAMP,paid_amount DECIMAL(24,6),payment_status_code VARCHAR,deleted INT,payment_owner_code VARCHAR,payment_owner_evidence VARCHAR)");
        jdbc.execute("ALTER TABLE order_sales_order ADD data_quality_status_code VARCHAR DEFAULT 'COMPLETE'");
        jdbc.execute("ALTER TABLE order_sales_order ADD source_system_code VARCHAR DEFAULT 'DHB'");
        jdbc.execute("CREATE TABLE order_attribution_snapshot(tenant_id VARCHAR,order_id BIGINT,employee_code VARCHAR,state VARCHAR)");
        jdbc.execute("CREATE TABLE order_refund_record(tenant_id VARCHAR,order_id BIGINT,refund_amount DECIMAL(24,6),refund_time TIMESTAMP,refund_status_code VARCHAR,deleted INT)");
        scopes = mock(OrderDataScope.class);
        when(scopes.predicate(anyString(), eq("o."), isNull())).thenReturn(new OrderDataScope.Predicate("o.region_code=?", List.of("allowed")));
        when(scopes.paymentPredicate(anyString(),eq("o."),eq("p."))).thenReturn(new OrderDataScope.Predicate("o.region_code=?",List.of("allowed")));
        jdbc.execute("CREATE VIEW order_verified_payment_v AS SELECT p.*,payment_time allocation_effective_at,payment_time actual_cash_at FROM order_payment_record p WHERE deleted=0 AND payment_status_code IN ('RECEIVED','CHECKED')");
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
        jdbc.update("INSERT INTO order_payment_record VALUES(?,'tenant',?,?,?, ?,?,0,(SELECT owner_employee_code FROM order_sales_order WHERE id=?),'confirmed')",id,order,"PAY-"+id,Timestamp.from(Instant.parse(time)),amount,status,order);
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
    @Test void fullPaymentCountsConfirmedCollectorContributionOnceInSettlementMonth() {
        receipt(1,2,"2026-10-10T00:00:00Z",150,"RECEIVED");
        receipt(2,2,"2026-10-31T16:00:00Z",50,"CHECKED");
        receipt(3,2,"2026-11-04T00:00:00Z",20,"RECEIVED");
        receipt(4,1,"2026-11-02T00:00:00Z",40,"RECEIVED");
        receipt(5,1,"2026-11-03T00:00:00Z",60,"CHECKED");
        // Later order ownership changes do not transfer confirmed receipt contributions.
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

    @Test void configuredSelfScopeIncludesHistoricalOrdersAndRoleChangesApplyBeforeWarmCache() {
        jdbc.execute("ALTER TABLE order_sales_order ADD created_by VARCHAR");
        jdbc.execute("ALTER TABLE order_sales_order ADD customer_id BIGINT");
        jdbc.execute("ALTER TABLE order_sales_order ADD store_id BIGINT");
        jdbc.update("UPDATE order_sales_order SET customer_id=id");
        var iam = mock(com.rigour.tenant.iam.client.SupplyAuthorizationClient.class);
        var callerId = java.util.UUID.randomUUID();
        var tenantId = java.util.UUID.randomUUID();
        var caller = new com.rigour.shared.context.CallerIdentity("TENANT",callerId,tenantId,callerId,null,
                java.util.UUID.randomUUID(),0,0,0,java.util.Set.of(),java.util.Set.of("order:read","order:payment:read"));
        com.rigour.shared.context.TestAuthorizationContext.set(caller);
        var mode = new java.util.concurrent.atomic.AtomicReference<>("SELF");
        var allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
        var all = new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.Limit("ALL",List.of());
        when(iam.authorization(any(),anyString())).thenAnswer(call ->
                new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView("ACTIVE",tenantId,callerId,"self",
                        1,1,1,1,java.util.Set.of(call.getArgument(1,String.class)),call.getArgument(1,String.class),allowed.get(),
                        List.of(new com.rigour.tenant.iam.api.v1.model.SupplyAuthorizationView.Clause(
                                java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),"ORDER",mode.get(),all,all,all,false)),all,all));
        try {
            scopes = new OrderDataScope(iam,jdbc);
            store = new JdbcOrderWorkbenchStore(jdbc,scopes);
            enableCache();
            // Real role policy, no frozen attribution rows; another region is still this employee's data.
            var summary = store.summary("tenant","self","order:read",from,until);
            assertThat(summary.tradeAmount()).isEqualByComparingTo("1099");
            assertThat(summary.pendingAmount()).isEqualByComparingTo("1099");
            assertThat(store.details("tenant","self","order:read","orders",from,until,0,20).total()).isEqualTo(2);
            assertThat(store.summary("tenant","other","order:read",from,until).orderCount()).isZero();
            receipt(1,1,"2026-11-02T00:00:00Z",100,"RECEIVED");
            assertThat(store.details("tenant","self","order:payment:read","receipts",from,until,0,20).amount()).isEqualByComparingTo("100");
            assertThat(store.performanceTotals("tenant","self","order:payment:read",from,until).amount()).isEqualByComparingTo("100");
            assertThat(store.details("tenant","other","order:read","orders",from,until,0,20).items()).isEmpty();
            receipt(50,3,"2026-11-02T00:00:00Z",999,"RECEIVED");
            jdbc.update("UPDATE order_payment_record SET payment_owner_code='self' WHERE id=50");
            cacheVersion++;
            assertThat(store.performanceTotals("tenant","self","order:payment:read",from,until).amount()).isEqualByComparingTo("1099");
            assertThat(store.details("tenant","other","order:payment:read","receipts",from,until,0,20).items()).isEmpty();
            assertThat(store.details("tenant","self","order:payment:read","receipts",from,until,0,20).total()).isEqualTo(2);
            assertThat(store.summary("other","self","order:read",from,until).orderCount()).isEqualTo(1);
            assertThat(store.customerSummary("tenant","self",from,until).purchasing()).isEqualTo(2);
            assertThat(store.customerSummary("tenant","other",from,until).purchasing()).isZero();
            assertThat(store.customerOrders("tenant","other","C3",0,20).items()).isEmpty();
            assertThat(store.customers("tenant","self","COOPERATED",from,until,"",0,20).items())
                    .extracting(r -> r.key()).containsExactly("C1","C5","C2");
            // Broad access is granted only by a changed role, never inferred from the query parameter.
            mode.set("ALL");
            assertThat(store.customerSummary("tenant","other",from,until).purchasing()).isEqualTo(1);
            assertThat(store.customerOrders("tenant","other","C3",0,20).total()).isEqualTo(1);
            assertThat(store.summary("tenant","other","order:read",from,until).orderCount()).isEqualTo(1);
            mode.set("SELF");
            assertThat(store.customerSummary("tenant","other",from,until).purchasing()).isZero();
            assertThat(store.summary("tenant","other","order:read",from,until).orderCount()).isZero();
            mode.set("NONE");
            assertThat(store.summary("tenant","self","order:read",from,until).orderCount()).isZero();
            mode.set("DEPARTMENT");
            assertThat(store.summary("tenant","self","order:read",from,until).orderCount()).isZero();
            mode.set("SELF"); allowed.set(false);
            assertThatThrownBy(() -> store.customerSummary("tenant","self",from,until))
                    .isInstanceOf(com.rigour.shared.context.AuthorizationDeniedException.class);
            assertThatThrownBy(() -> store.summary("tenant","self","order:read",from,until))
                    .isInstanceOf(com.rigour.shared.context.AuthorizationDeniedException.class);
        } finally { com.rigour.shared.context.TestAuthorizationContext.clear(); }
    }

    @Test void customerMetricsIncludeUnlinkedHistoryDeduplicateAndUseCompanyFirstOrder() {
        jdbc.execute("ALTER TABLE order_sales_order ADD customer_id BIGINT");
        jdbc.execute("ALTER TABLE order_sales_order ADD store_id BIGINT");
        jdbc.update("UPDATE order_sales_order SET customer_id=11 WHERE id IN (1,2)");
        jdbc.update("UPDATE order_sales_order SET customer_id=12,order_date=? WHERE id=3",Timestamp.from(from.minusSeconds(86400)));
        jdbc.update("UPDATE order_sales_order SET customer_id=13 WHERE id=4");
        order(8,"tenant","self","allowed","SUBMITTED","2026-11-03T00:00:00Z",50,20);
        order(9,"tenant","self","allowed","SUBMITTED","2026-11-04T00:00:00Z",60,20);
        order(10,"tenant","self","allowed","COMPLETED","2026-11-05T00:00:00Z",70,0);
        order(11,"tenant","self","allowed","SUBMITTED","2026-11-06T00:00:00Z",80,0);
        jdbc.update("UPDATE order_sales_order SET customer_id=12 WHERE id=8");
        jdbc.update("UPDATE order_sales_order SET customer_id=13 WHERE id IN (9,10)");
        jdbc.update("UPDATE order_sales_order SET store_id=14 WHERE id=11");
        var stats=store.customerSummary("tenant","self",from,until);
        assertThat(stats.purchasing()).isEqualTo(4);
        assertThat(stats.newCooperation()).isEqualTo(2);
        assertThat(stats.returning()).isEqualTo(2);
        assertThat(stats.cooperated()).isEqualTo(4);
        assertThat(store.customers("tenant","self","NEW",from,until,"",0,20).items())
                .extracting(r -> r.key()).containsExactly("S14","C13");
        assertThat(store.customers("tenant","self","RETURNING",from,until,"",0,20).items())
                .extracting(r -> r.key()).containsExactly("C12","C11");
        assertThat(store.customers("tenant","self","PURCHASING",from,until,"",0,1).total()).isEqualTo(4);
        assertThat(store.customers("tenant","self","PURCHASING",from,until,"不存在",0,20).items()).isEmpty();
        var orders=store.customerOrders("tenant","self","C12",0,20);
        assertThat(orders.items()).extracting(r -> r.orderId()).containsExactly("8");
        assertThat(orders.amount()).isEqualByComparingTo("50");
        assertThat(store.customerOrders("tenant","self","S14",0,20).items()).extracting(r -> r.orderId()).containsExactly("11");
        assertThat(store.customerOrders("other","self","C11",0,20).items()).isEmpty();
        enableCache();store.customerSummary("tenant","self",from,until);
        when(scopes.predicate(anyString(),eq("o."),isNull())).thenReturn(new OrderDataScope.Predicate("1=0",List.of()));
        assertThat(store.customerSummary("tenant","self",from,until).purchasing()).isZero();
        assertThat(store.customers("tenant","self","COOPERATED",from,until,"",0,20).items()).isEmpty();
        assertThat(store.customerOrders("tenant","self","C11",0,20).items()).isEmpty();
    }

    @Test void sharedCollectorsReceiveOnlyTheirContributionInSettlementMonthNotOrderOrReceiptMonth() {
        order(20,"tenant","seller","allowed","COMPLETED","2026-09-01T00:00:00Z",1000,0);
        receipt(60,20,"2026-10-15T00:00:00Z",300,"RECEIVED");
        receipt(61,20,"2026-11-05T00:00:00Z",700,"CHECKED");
        receipt(62,20,"2026-11-05T00:00:00Z",100,"RECEIVED");
        receipt(63,20,"2026-10-01T00:00:00Z",1000,"CANCELLED");
        jdbc.update("UPDATE order_payment_record SET payment_owner_code='self' WHERE id=60");
        jdbc.update("UPDATE order_payment_record SET payment_owner_code='collector-b' WHERE id IN (61,62)");
        assertThat(store.performanceTotals("tenant","self","order:payment:read",from,until).amount()).isEqualByComparingTo("300");
        assertThat(store.performanceTotals("tenant","collector-b","order:payment:read",from,until).amount()).isEqualByComparingTo("700");
        assertThat(store.performanceTotals("tenant","seller","order:payment:read",from,until).total()).isZero();
        assertThat(store.performanceTotals("tenant","self","order:payment:read",from.minusSeconds(86400*31),from).total()).isZero();
        var page=store.details("tenant","self","order:payment:read","performance",from,until,0,20);
        assertThat(page.items()).hasSize(1); assertThat(page.items().getFirst().amount()).isEqualByComparingTo("300");
        assertThat(page.items().getFirst().occurredAt()).isEqualTo(Instant.parse("2026-11-05T00:00:00Z"));
        jdbc.update("UPDATE order_payment_record SET payment_owner_evidence=NULL WHERE id=60");
        assertThat(store.performanceTotals("tenant","self","order:payment:read",from,until).total()).isZero();
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
