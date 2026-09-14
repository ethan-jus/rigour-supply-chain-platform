package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.BiReconciliationReview;
import com.rigour.analytics.api.v1.model.BiReconciliationReview.*;
import com.rigour.analytics.application.port.out.BiReconciliationReviewStore;
import com.rigour.analytics.application.port.out.BiReconciliationReviewStore.OnlineCapture;
import com.rigour.order.api.v1.model.SalesOrderProductRepair.Evidence;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TestAuthorizationContext;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import static com.rigour.analytics.application.service.BiReconciliationReviewServiceTest.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 通过真实复核用例验证不同来源版本不共用人工确认，只模拟出站读取和复核快照保存。 */
class BiReconciliationRepairCaptureTest {
    private static final String PREVIOUS="22222222-2222-4222-8222-222222222222";
    private static final String CAPTURE="33333333-3333-4333-8333-333333333333";
    private final JsonMapper json=JsonMapper.builder().build();

    @AfterEach void clear() { TestAuthorizationContext.clear(); }

    @Test void onlineCaptureConfirmationNeverFlowsIntoAnOlderFileBatch() { verifyCapture(true,false); }
    @Test void fileBatchConfirmationNeverFlowsIntoAnotherFileBatch() { verifyCapture(false,false); }
    @Test void repeatedSourceLinesRemainUnverifiedThroughCaptureAndComparison() { verifyCapture(true,true); }

    private void verifyCapture(boolean online,boolean duplicate) {
        var tenant=UUID.randomUUID(); var user=UUID.randomUUID();
        TestAuthorizationContext.set(new CallerIdentity("TENANT",user,tenant,user,null,UUID.randomUUID(),1,1,1,
                Set.of("TENANT_SUPER_ADMIN"),Set.of("analytics:dashboard:read","analytics:reconciliation:write","order:read")));
        var store=mock(BiReconciliationReviewStore.class);
        var scope=mock(BiDataScopeService.class);
        var business=business();
        when(store.businessRows(tenant.toString(),20001)).thenReturn(business);
        when(store.biRows(tenant.toString(),20001)).thenReturn(business);
        var previous=new Version(PREVIOUS,"older.xlsx",null,null,NOW.minusSeconds(3600),"PREFLIGHTED",2);
        when(store.version(tenant.toString(),PREVIOUS)).thenReturn(Optional.of(previous));
        when(store.sourceRows(tenant.toString(),PREVIOUS,20001)).thenReturn(raw(false));
        if (online) {
            var provenance=new OnlineEvidence(CAPTURE,"source","来源",null,NOW.minusSeconds(120),NOW.minusSeconds(60),true,false,2,2,false);
            var version=new Version(null,"来源",null,null,NOW.minusSeconds(60),"CAPTURED_NON_ATOMIC",2,provenance);
            when(store.onlineCapture(tenant.toString(),CAPTURE)).thenReturn(Optional.of(new OnlineCapture(version,raw(duplicate),true)));
        } else {
            when(store.version(tenant.toString(),BATCH)).thenReturn(Optional.of(VERSION));
            when(store.sourceRows(tenant.toString(),BATCH,20001)).thenReturn(raw(duplicate));
        }
        var evidence=json.readValue("""
                {"lineId":11,"productVariantId":22,"sourceOrderNo":"DD1","sourceProductCode":"P1",
                 "sourceIdentityStatus":"OPERATOR_CONFIRMED","transactionUnitStatus":"OPERATOR_CONFIRMED",
                 "historicalTransactionUnitCode":"BUCKET","storedUnitCode":"BUCKET","transactionQuantity":12}
                """,Evidence.class);
        var evidenceJson=(tools.jackson.databind.node.ObjectNode) json.valueToTree(evidence);
        evidenceJson.put("sourceNamespace",online?"source":BATCH);
        evidenceJson.put("sourceCaptureRef",online?CAPTURE:BATCH);
        var confirmed=json.treeToValue(evidenceJson,Evidence.class);
        var service=new BiReconciliationReviewService(store,scope,Clock.fixed(NOW,ZoneOffset.UTC),json,
                actor->Map.of("BOX","箱","BUCKET","桶"),actor->List.of(confirmed));
        service.capture(new Command(online?null:BATCH,PREVIOUS,COMMAND.from(),NOW,null,true,online?CAPTURE:null));
        var saved=ArgumentCaptor.forClass(BiReconciliationReview.class);
        verify(store).save(eq(tenant.toString()),eq(user.toString()),saved.capture());
        var sku=saved.getValue().rows().stream().filter(r->"SKU|DD1|NOODLE".equals(r.key())).findFirst().orElseThrow();
        assertThat(sku.previous().confirmedUnitCode()).isNull();
        assertThat(sku.previous().unitEvidence()).isEqualTo("COLUMN_INFERRED");
        assertThat(sku.previous().associationEvidence()).isEqualTo("SOURCE_CODE");
        assertThat(sku.previous().rawUnit()).isEqualTo("箱");
        assertThat(sku.previous().quantity()).isEqualByComparingTo("12");
        assertThat(sku.previous().amount()).isEqualByComparingTo("100");
        if (duplicate) {
            assertThat(saved.getValue().rows()).filteredOn(r->"SKU".equals(r.kind()) && r.source()!=null)
                    .hasSize(2).allSatisfy(r->{
                        assertThat(r.source().confirmedUnitCode()).isNull();
                        assertThat(r.source().unitEvidence()).isEqualTo("COLUMN_INFERRED");
                        assertThat(r.source().quantity()).isEqualByComparingTo("6");
                        assertThat(r.source().amount()).isEqualByComparingTo("50");
                        assertThat(r.associationStatus()).isNotEqualTo("SNAPSHOT_MATCH");
                    });
            assertThat(sku.quantityStatus()).isEqualTo("UNVERIFIED");
            assertThat(sku.status()).isNotEqualTo("SNAPSHOT_MATCH");
        } else {
            assertThat(sku.source().confirmedUnitCode()).isEqualTo("BUCKET");
            assertThat(sku.source().associationEvidence()).isEqualTo("OPERATOR_CONFIRMED");
            assertThat(sku.source().rawUnit()).isEqualTo("箱");
            assertThat(sku.source().quantity()).isEqualByComparingTo("12");
            assertThat(sku.source().amount()).isEqualByComparingTo("100");
        }
        assertThat(business).isEqualTo(business());
        assertThat(sku.business().quantity()).isEqualByComparingTo("12");
        assertThat(sku.business().amount()).isEqualByComparingTo("100");
    }

    private List<Fact> business() {
        var rows=facts("DD1","100");
        var f=rows.get(1);
        return List.of(rows.getFirst(),new Fact(f.key(),f.orderNo(),f.kind(),f.city(),f.sales(),f.customer(),f.product(),
                f.specification(),"BUCKET",f.orderDate(),f.amount(),f.paid(),f.unpaid(),new java.math.BigDecimal("12"),
                f.updatedAt(),f.sourceRows(),false,List.of(),"BUCKET",null,"SYSTEM","SYSTEM",null,null,null,"11","22",null));
    }

    private List<Map<String,Object>> raw(boolean duplicate) {
        var header=BiReconciliationSourceNormalizerTest.online("FEISHU_SALES_ORDER","header",json.writeValueAsString(Map.of(
                "订单编号","DD1","销售日期",DATE.toString(),"城市","北京","销售","销售甲","门店","客户",
                "数量",12,"实际小计",100,"收款合计",0,"待付金额",100)));
        String fields=json.writeValueAsString(Map.of("关联订单","DD1","订单产品","方便面","SKU编码","NOODLE",
                "产品编码","P1","数量(箱)",duplicate?6:12,"实际小计",duplicate?50:100));
        var first=BiReconciliationSourceNormalizerTest.online("FEISHU_SALES_ORDER_LINE","line-1",fields);
        return duplicate?List.of(header,first,BiReconciliationSourceNormalizerTest.online("FEISHU_SALES_ORDER_LINE","line-2",fields))
                :List.of(header,first);
    }
}
