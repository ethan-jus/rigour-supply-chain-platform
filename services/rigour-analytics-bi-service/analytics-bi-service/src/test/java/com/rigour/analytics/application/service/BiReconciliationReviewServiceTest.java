package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.BiReconciliationReview;
import com.rigour.analytics.api.v1.model.BiReconciliationReview.*;
import com.rigour.analytics.application.port.out.BiReconciliationReviewStore;
import com.rigour.analytics.application.port.out.SupplyDashboardStore;
import com.rigour.shared.context.TestAuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.AuthorizationDeniedException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BiReconciliationReviewServiceTest {
    static final Instant NOW=Instant.parse("2026-09-12T09:00:00Z");
    static final Instant DATE=Instant.parse("2026-09-01T00:00:00Z");
    static final String BATCH="11111111-1111-1111-1111-111111111111";
    static final Version VERSION=new Version(BATCH,"source.xlsx","a".repeat(64),"https://example.feishu.cn/base/source",NOW.minusSeconds(60),"PREFLIGHTED",2);
    static final Command COMMAND=new Command(BATCH,null,Instant.parse("2026-09-01T00:00:00Z"),NOW,null,true);
    @AfterEach void clear() { TestAuthorizationContext.clear(); }

    @Test void absentSourceNeverPassesAndTotalRemainsUnknown() {
        var r=review(List.of(),facts("DD1","100"),facts("DD1","100"));
        assertThat(r.status()).isEqualTo("UNVERIFIED");
        var page=page(r,"ORDER",1,20);
        assertThat(page.summary().sourceAmount()).isNull();
        assertThat(page.rows().getFirst().status()).isEqualTo("UNVERIFIED");
        assertThat(r.onlineStatus()).isEqualTo("UNVERIFIED");
    }
    @Test void legitimateZeroIsComparableRatherThanMissing() {
        var r=review(facts("DD1","0"),facts("DD1","0"),facts("DD1","0"));
        assertThat(r.status()).isEqualTo("SNAPSHOT_MATCH");
        assertThat(page(r,"ORDER",1,20).summary().sourceAmount()).isEqualByComparingTo("0");
        assertThat(r.onlineStatus()).isEqualTo("UNVERIFIED");
    }
    @Test void zeroSourceAgainstPositiveBusinessIsDifference() {
        var r=review(facts("DD1","0"),facts("DD1","20"),facts("DD1","20"));
        assertThat(r.status()).isEqualTo("DIFF");
    }
    @Test void refundedQuantityZeroIsAuditExclusionNotLostOrderOrMissingCollection() {
        var original=fact("DD1","ORDER","0");
        var excluded=new Fact(original.key(),original.orderNo(),original.kind(),"北京","销售甲","客户",null,null,null,DATE,
                BigDecimal.ZERO,new BigDecimal("205"),new BigDecimal("-205"),BigDecimal.ZERO,null,"订单:15",true,List.of());
        var r=review(List.of(excluded),List.of(),List.of());
        var page=page(r,"ORDER",1,20);
        assertThat(page.rows().getFirst().status()).isEqualTo("EXCLUDED_REFUND");
        assertThat(page.rows().getFirst().issues()).noneMatch(x->x.contains("缺失"));
        assertThat(page.summary().excluded()).isEqualTo(1);
        assertThat(page.summary().sourcePaid()).isNull();
        assertThat(page.rows().getFirst().source().paid()).isEqualByComparingTo("205");
    }
    @Test void previouslyExcludedRefundStillInBusinessMustBeReviewed() {
        var a=fact("DD1","ORDER","0");
        var source=new Fact(a.key(),a.orderNo(),a.kind(),a.city(),a.sales(),a.customer(),null,null,null,DATE,
                a.amount(),a.paid(),a.unpaid(),BigDecimal.ZERO,null,"row1",true,List.of());
        var r=review(List.of(source),List.of(a),List.of(a));
        assertThat(r.rows().getFirst().status()).isEqualTo("DIFF");
    }
    @Test void mismatchedDateScopeCannotPassEvenWhenAmountsMatch() {
        var data=new ArrayList<>(facts("DD1","100"));
        var f=data.getFirst();
        data.set(0,new Fact(f.key(),f.orderNo(),f.kind(),f.city(),f.sales(),f.customer(),f.product(),f.specification(),f.unit(),
                DATE.minusSeconds(86400),f.amount(),f.paid(),f.unpaid(),f.quantity(),f.updatedAt(),null,false,List.of()));
        var r=review(facts("DD1","100"),data,facts("DD1","100"));
        assertThat(page(r,"ORDER",1,20).rows().getFirst().issues()).anyMatch(x->x.contains("筛选范围"));
        assertThat(r.status()).isEqualTo("UNVERIFIED");
    }
    @Test void staleSnapshotDoesNotCertifyCurrentOnline() {
        var old=new Version(BATCH,"old.xlsx","b".repeat(64),null,NOW.minusSeconds(86400),"SUCCEEDED",2);
        var r=BiReconciliationReviewService.compare("id",COMMAND,old,null,facts("DD1","100"),facts("DD1","100"),facts("DD1","100"),List.of(),NOW,NOW);
        assertThat(r.status()).isNotIn("PASS","SNAPSHOT_MATCH");
        assertThat(r.rows()).anyMatch(x->"STALE".equals(x.status()));
    }
    @Test void incompleteExportDoesNotCertifyMissingOrMatchingRows() {
        var c=new Command(BATCH,null,COMMAND.from(),COMMAND.to(),null,false);
        var r=BiReconciliationReviewService.compare("id",c,VERSION,null,facts("DD1","100"),facts("DD1","100"),facts("DD1","100"),List.of(),NOW,NOW);
        assertThat(r.status()).isEqualTo("UNVERIFIED");
    }
    @Test void filteringBeforePaginationIsDeterministicAndBounded() {
        List<Fact> rows=new ArrayList<>();
        for(int n=0;n<11;n++) rows.addAll(facts("DD"+String.format("%02d",n),"10"));
        var r=review(rows,rows,rows);
        var first=page(r,"ORDER",1,4); var second=page(r,"ORDER",2,4);
        assertThat(first.total()).isEqualTo(11);
        assertThat(first.rows()).hasSize(4);
        assertThat(second.rows()).extracting(Row::orderNo).containsExactly("DD04","DD05","DD06","DD07");
        assertThat(page(r,"ORDER",99,4).rows()).isEmpty();
        var searched=BiReconciliationReviewService.page(r,"ORDER",null,null,null,null,"DD10",1,4);
        assertThat(searched.total()).isEqualTo(1);
        assertThatThrownBy(()->page(r,"ORDER",0,4)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->page(r,"ORDER",1,101)).isInstanceOf(RuntimeException.class);
    }
    @Test void twoSourceVersionsExposeChangesWithoutRestoringOldLinesOrPayments() {
        var previous=new Version("22222222-2222-2222-2222-222222222222","old.xlsx","b".repeat(64),null,NOW.minusSeconds(86400),"SUCCEEDED",2);
        var r=BiReconciliationReviewService.compare("id",COMMAND,VERSION,previous,facts("DD1","78"),facts("DD1","78"),facts("DD1","78"),facts("DD1","234"),NOW,NOW);
        assertThat(r.rows()).allMatch(x->"CHANGED".equals(x.versionStatus()));
        assertThat(r.status()).isEqualTo("SNAPSHOT_MATCH");
        assertThat(r.rows().getFirst().previous().amount()).isEqualByComparingTo("234");
    }
    @Test void skuDiscrepancyIsNotHiddenByMatchingHeaderTotals() {
        var source=facts("DD1","100");
        var business=new ArrayList<>(facts("DD1","100")); business.set(1,fact("DD1","SKU","99"));
        var r=review(source,business,business);
        assertThat(page(r,"ORDER",1,20).rows().getFirst().status()).isEqualTo("DIFF");
    }
    @Test void nonGlobalCallerIsDeniedBeforeAnyRead() {
        var store=mock(BiReconciliationReviewStore.class); var scope=mock(BiDataScopeService.class);
        doThrow(new AuthorizationDeniedException("bi-global-governance")).when(scope).requireGlobalGovernance();
        var service=new BiReconciliationReviewService(store,scope,Clock.fixed(NOW,ZoneOffset.UTC),JsonMapper.builder().build(),ignored->Map.of());
        assertThatThrownBy(()->service.capture(COMMAND)).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store);
    }
    @Test void readPermissionCannotCreatePersistentReviewEvidence() {
        var store=mock(BiReconciliationReviewStore.class); var scope=mock(BiDataScopeService.class);
        UUID user=UUID.randomUUID();
        TestAuthorizationContext.set(new CallerIdentity("TENANT",user,UUID.randomUUID(),user,null,UUID.randomUUID(),1,1,1,
                Set.of("TENANT_SUPER_ADMIN"),Set.of("analytics:dashboard:read")));
        var service=new BiReconciliationReviewService(store,scope,Clock.fixed(NOW,ZoneOffset.UTC),JsonMapper.builder().build(),ignored->Map.of());
        assertThatThrownBy(()->service.capture(COMMAND)).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store);
    }
    @Test void pageReadUsesOnlyTheStoredReviewAndCurrentActor() {
        var store=mock(BiReconciliationReviewStore.class); var scope=mock(BiDataScopeService.class);
        UUID user=UUID.randomUUID(),tenant=UUID.randomUUID();
        TestAuthorizationContext.set(new CallerIdentity("TENANT",user,tenant,user,null,UUID.randomUUID(),1,1,1,
                Set.of("TENANT_SUPER_ADMIN"),Set.of("analytics:dashboard:read")));
        var review=review(facts("DD1","0"),facts("DD1","0"),facts("DD1","0"));
        when(store.find(tenant.toString(),user.toString(),BATCH)).thenReturn(Optional.of(review));
        var service=new BiReconciliationReviewService(store,scope,Clock.fixed(NOW,ZoneOffset.UTC),JsonMapper.builder().build(),ignored->Map.of());
        assertThat(service.get(BATCH,"ORDER",null,null,null,null,null,1,50).total()).isEqualTo(1);
        verify(store).find(tenant.toString(),user.toString(),BATCH);
        verifyNoMoreInteractions(store);
        verify(scope).requireGlobalGovernance();
    }
    @Test void businessBiDifferenceRemainsVisibleWhenExternalSourceIsUnavailable() {
        var r=review(List.of(),facts("DD1","100"),facts("DD1","90"));
        assertThat(r.status()).isEqualTo("DIFF");
        assertThat(r.onlineStatus()).isEqualTo("UNVERIFIED");
    }
    @Test void missingCityFacetDoesNotLoseUnknownAttributionOrders() {
        var f=fact("DD1","ORDER","100");
        var unassigned=new Fact(f.key(),f.orderNo(),f.kind(),null,f.sales(),f.customer(),null,null,null,DATE,
                f.amount(),f.paid(),f.unpaid(),f.quantity(),null,null,false,List.of("来源城市缺失"));
        var r=review(List.of(unassigned),List.of(unassigned),List.of(unassigned));
        var page=BiReconciliationReviewService.page(r,"ORDER",null,"未归属",null,null,null,1,50);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.cities()).extracting(Dimension::name).contains("未归属");
    }
    @Test void legacyZeroSentinelsDoNotProducePass() {
        var store=mock(SupplyDashboardStore.class);
        when(store.reconciliation(anyString(),any())).thenReturn(new SupplyDashboardStore.ReconciliationData(COMMAND.from(),COMMAND.to(),NOW,List.of(
                new SupplyDashboardStore.ReconciliationItem("SALES_ORDER_LINE","订单行",0L,2L,2L,BigDecimal.ZERO,new BigDecimal("100"),new BigDecimal("100")))));
        UUID user=UUID.randomUUID();
        TestAuthorizationContext.set(new CallerIdentity("TENANT",user,UUID.randomUUID(),user,null,UUID.randomUUID(),1,1,1,Set.of("TENANT_SUPER_ADMIN"),Set.of("analytics:dashboard:read")));
        var service=new SupplyDashboardGovernanceService(store,Clock.fixed(NOW,ZoneOffset.UTC));
        var r=service.reconciliation(COMMAND.from(),COMMAND.to(),"BEIJING",null,null,null,"FEISHU");
        assertThat(r.status()).isEqualTo("UNVERIFIED");
        assertThat(r.items().getFirst().sourceBusinessAmountDiff()).isNull();
        assertThat(r.items().getFirst().sourceBusinessRowDiff()).isNull();
    }
    static BiReconciliationReview review(List<Fact> source,List<Fact> business,List<Fact> bi) {
        return BiReconciliationReviewService.compare("id",COMMAND,VERSION,null,source,business,bi,List.of(),NOW,NOW);
    }
    static Page page(BiReconciliationReview r,String kind,int page,int size) {
        return BiReconciliationReviewService.page(r,kind,null,null,null,null,null,page,size);
    }
    static List<Fact> facts(String no,String amount) { return List.of(fact(no,"ORDER",amount),fact(no,"SKU",amount)); }
    static Fact fact(String no,String kind,String amount) {
        return new Fact(kind+"|"+no+("SKU".equals(kind)?"|NOODLE":""),no,kind,"北京","销售甲","客户",
                "SKU".equals(kind)?"方便面":null,null,"SKU".equals(kind)?"箱":null,DATE,new BigDecimal(amount),
                "ORDER".equals(kind)?BigDecimal.ZERO:null,"ORDER".equals(kind)?new BigDecimal(amount):null,
                BigDecimal.ONE,NOW.minusSeconds(60),null,false,List.of());
    }
}
