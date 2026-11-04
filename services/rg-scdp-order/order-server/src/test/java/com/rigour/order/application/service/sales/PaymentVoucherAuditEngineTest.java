package com.rigour.order.application.service.sales;

import com.rigour.order.api.v1.model.PaymentVoucherAuditModels.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PaymentVoucherAuditEngineTest {
    Evidence e(String key,String amount,String no) {return new Evidence(key,amount==null?null:new BigDecimal(amount),no,"",Instant.parse("2026-09-01T00:00:00Z"),"SECOND",null,null,null);}
    Payment p(String id,String amount,boolean excluded,String primary,Evidence... e) {
        return new Payment(id,"PAY-"+id,id,"SO-"+id,"客户","销售",new BigDecimal(amount),Instant.parse("2026-09-01T00:00:00Z"),excluded?"CANCELLED":"CHECKED",excluded,primary,Arrays.stream(e).map(Evidence::key).toList(),List.of(e));
    }
    AuditGroup transaction(List<Payment> p,String n) {return PaymentVoucherAuditEngine.scan(p,Map.of()).groups().stream().filter(g->n.equals(g.transactionNo())).findFirst().orElseThrow();}
    @Test void combinedPaymentCountsVoucherOnceAndIgnoresCancelledAndDuplicateImages() {
        var group=transaction(List.of(p("1","702",false,"X",e("a","1625.4","X"),e("b","1625.4","X")),p("2","248.4",false,"X",e("a","1625.4","X")),p("3","216",false,"X",e("a","1625.4","X")),p("4","108",false,"X",e("a","1625.4","X")),p("5","351",false,"X",e("a","1625.4","X")),p("6","900",true,"X",e("a","1625.4","X"))),"X");
        assertThat(group.result()).isEqualTo("BALANCED");assertThat(group.allocatedAmount()).isEqualByComparingTo("1625.4");assertThat(group.voucherAmount()).isEqualByComparingTo("1625.4");
    }
    @Test void repeatedFullReceiptExceedsPaymentEvenAcrossDates() {
        var group=transaction(List.of(p("1","300",false,"X",e("a","300","X")),p("2","300",false,"X",e("a","300","X")),p("3","300",false,"X",e("a","300","X"))),"X");
        assertThat(group.result()).isEqualTo("EXCESS");assertThat(group.excessAmount()).isEqualByComparingTo("600");
    }
    @Test void splitsMultipleVouchersAndDoesNotCopyParentAmountToEachTransaction() {
        var payments=List.of(p("1","500",false,"A",e("a","200","A"),e("b","300","B")));
        assertThat(transaction(payments,"A").allocatedAmount()).isEqualByComparingTo("200");
        assertThat(transaction(payments,"B").result()).isEqualTo("BALANCED");
        var example=transaction(List.of(p("2","444.60",false,"C",e("c","148.20","C"),e("d","296.40",null))),"C");
        assertThat(example.allocatedAmount()).isEqualByComparingTo("148.20"); assertThat(example.result()).isEqualTo("BALANCED");
    }
    @Test void ambiguousSplitAndAmountConflictRemainPending() {
        var g=transaction(List.of(p("1","500",false,"A",e("a","800","A"),e("b","300","B"))),"A");
        assertThat(g.result()).isEqualTo("PENDING_ALLOCATION");assertThat(g.unresolvedPayments()).isEqualTo(1);
        assertThat(transaction(List.of(p("1","300",false,"A",e("a","300","A")),p("2","300",false,"A",e("b","301","A"))),"A").result()).isEqualTo("CONFLICT");
    }
    @Test void singlePaymentDifferenceIsNotLabeledRepeatedReceipt() {
        var g=transaction(List.of(p("1","78",false,"X",e("a","77.94","X"))),"X");
        assertThat(g.result()).isEqualTo("BALANCED"); assertThat(g.excessAmount()).isEqualByComparingTo("0.06");
    }
    @Test void missingNumberAndReusedImageWithDifferentNumbersAreNotMissed() {
        var scan=PaymentVoucherAuditEngine.scan(List.of(p("1","300",false,"A",e("same","300","A")),p("2","300",false,"B",e("same","300","B")),p("3","40",false,null,e("none",null,null))),Map.of());
        assertThat(scan.groups()).anyMatch(g->g.kind().equals("IMAGE")&&g.result().equals("EXCESS"));
        assertThat(scan.groups()).anyMatch(g->g.kind().equals("PAYMENT")&&g.result().equals("MISSING_EVIDENCE"));
    }
    @Test void reviewBecomesStaleWhenEvidenceChangesAndHiddenPaymentReviewIsNotLeaked() {
        var old=transaction(List.of(p("1","300",false,"A",e("a","300","A"))),"A");
        var r=new Review("r",old.fingerprint(),"NORMAL_COMBINED","已核对","u",Instant.now(),List.of("1"));
        var newer=PaymentVoucherAuditEngine.scan(List.of(p("1","301",false,"A",e("a","300","A"))),Map.of(old.key(),List.of(r))).groups().get(0);
        assertThat(newer.reviewStale()).isTrue();
        var hidden=new Review("h",old.fingerprint(),"NORMAL_COMBINED","secret","u",Instant.now(),List.of("1","hidden"));
        assertThat(PaymentVoucherAuditEngine.scan(old.payments(),Map.of(old.key(),List.of(hidden))).groups().get(0).reviews()).isEmpty();
    }
    @Test void toleranceIsInclusiveAndReviewChangesClassificationWithoutChangingMoney() {
        for (String value : List.of("295", "300", "305")) {
            var group=transaction(List.of(p("1",value,false,"A",e("a","300","A"))),"A");
            assertThat(group.auditStatusCode()).isEqualTo("NORMAL");
            assertThat(group.reasonCodes()).isEmpty();
            assertThat(group.allocatedAmount()).isEqualByComparingTo(value);
        }
        var group=transaction(List.of(p("1","305.01",false,"A",e("a","300","A"))),"A");
        assertThat(group.auditStatusCode()).isEqualTo("PENDING_REVIEW");
        var normal=new Review("r",group.fingerprint(),"NORMAL","已核对凭证和分配","finance",Instant.now(),List.of("1"));
        var scan=PaymentVoucherAuditEngine.scan(group.payments(),Map.of(group.key(),List.of(normal)));
        assertThat(scan.groups().getFirst().auditStatusCode()).isEqualTo("NORMAL");
        assertThat(scan.groups().getFirst().reasonCodes()).isEmpty();
        assertThat(scan.groups().getFirst().allocatedAmount()).isEqualByComparingTo("305.01");
        assertThat(scan.counts().get("NORMAL")).isEqualTo(1L);
        var abnormal=new Review("r2",group.fingerprint(),"ABNORMAL","确认重复凭证","finance",Instant.now(),List.of("1"),List.of("INVALID_VOUCHER"));
        var reviewed=PaymentVoucherAuditEngine.scan(group.payments(),Map.of(group.key(),List.of(abnormal))).groups().getFirst();
        assertThat(reviewed.auditStatusCode()).isEqualTo("ABNORMAL");
        assertThat(reviewed.reasonCodes()).containsExactly("INVALID_VOUCHER");
    }
    @Test void contentHashMatchesArchivedCopiesAcrossPathsWithoutGuessingOrdinaryFilenames() {
        String hash = "a".repeat(64);
        var scan = PaymentVoucherAuditEngine.scan(List.of(
                p("1", "100", false, null, e("tenant/voucher-audit-20261009/" + hash + ".png", null, null)),
                p("2", "100", false, null, e("tenant/fund-attachments/FP2/" + hash + ".jpg", null, null)),
                p("3", "100", false, null, e("tenant/manual/" + hash + ".png", null, null))), Map.of());
        assertThat(scan.groups().stream().filter(g -> g.kind().equals("IMAGE")).toList())
                .singleElement().satisfies(g -> assertThat(g.payments()).extracting(Payment::id).containsExactly("1", "2"));
    }

    @Test void measuresFullAuthorizedScanWithTenThousandPayments() {
        var rows = new ArrayList<Payment>();
        for (int i = 0; i < 10000; i++) rows.add(p(Integer.toString(i), "10", false, "TX-" + (i / 2), e("image-" + (i / 2), "20", "TX-" + (i / 2))));
        long started = System.nanoTime();
        var scan = PaymentVoucherAuditEngine.scan(rows, Map.of());
        System.out.println("VOUCHER_AUDIT_BENCH payments=10000 groups=" + scan.groups().size() + " elapsed_ms=" + (System.nanoTime() - started) / 1000000);
        assertThat(scan.groups()).hasSize(5000).allSatisfy(g -> assertThat(g.allocatedAmount()).isEqualByComparingTo("20"));
    }

    @Test void amountMatchWithoutImageTimeOrSuccessfulRecognitionRemainsPending() {
        var incomplete=new Evidence("a",new BigDecimal("300"),"A","",null,null,"REVIEW",null,null);
        assertThat(transaction(List.of(p("1","300",false,"A",incomplete)),"A").auditStatusCode()).isEqualTo("PENDING_REVIEW");
        var recognized=new Evidence("a",new BigDecimal("300"),"A","",Instant.parse("2026-09-01T00:00:00Z"),"SECOND","RECOGNIZED","公司","SUCCESS");
        assertThat(transaction(List.of(p("1","300",false,"A",recognized)),"A").result()).isEqualTo("BALANCED");
        assertThat(transaction(List.of(p("1","300",false,"A",recognized)),"A").auditStatusCode()).isEqualTo("PENDING_REVIEW");
    }

}
