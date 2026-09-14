package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.BiReconciliationReview.Fact;
import com.rigour.analytics.api.v1.model.BiReconciliationReview.Row;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static com.rigour.analytics.application.service.BiReconciliationReviewServiceTest.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 列名提示不能变成交易单位结论，金额与数量关联分开验收。 */
class BiReconciliationEvidenceTest {
    @Test void inferredBoxesDoNotReportProvenMismatchAgainstSystemBuckets() {
        var source=List.of(fact("DD1","ORDER","100"),evidence("100","箱","COLUMN_INFERRED","SOURCE_CODE"));
        var business=List.of(fact("DD1","ORDER","100"),evidence("100","桶","SYSTEM","SYSTEM"));
        var result=review(normalize(source),normalize(business),normalize(business));
        assertThat(result.status()).isEqualTo("UNVERIFIED");
        var line=page(result,"SKU",1,50).rows().getFirst();
        assertThat(line.financialStatus()).isEqualTo("SNAPSHOT_MATCH");
        assertThat(line.quantityStatus()).isEqualTo("UNVERIFIED");
        assertThat(line.source().unitEvidence()).isEqualTo("COLUMN_INFERRED");
        assertThat(line.issues()).noneMatch(i->i.contains("原单位不同"));
        var order=page(result,"ORDER",1,50).rows().getFirst();
        assertThat(order.financialStatus()).isEqualTo("SNAPSHOT_MATCH");
        assertThat(order.quantityStatus()).isEqualTo("NOT_APPLICABLE");
    }
    @Test void uncertainUnitCannotHideAnActualFinancialDifference() {
        var source=List.of(fact("DD1","ORDER","100"),evidence("100","箱","COLUMN_INFERRED","SOURCE_CODE"));
        var business=List.of(fact("DD1","ORDER","100"),evidence("90","桶","SYSTEM","SYSTEM"));
        var line=page(review(normalize(source),normalize(business),normalize(business)),"SKU",1,50).rows().getFirst();
        assertThat(line.status()).isEqualTo("DIFF");
        assertThat(line.financialStatus()).isEqualTo("DIFF");
        assertThat(line.quantityStatus()).isEqualTo("UNVERIFIED");
    }
    @Test void exactNamesAreCandidatesNotVerifiedAssociations() {
        var source=List.of(fact("DD1","ORDER","100"),evidence("100","桶","EXPLICIT","EXACT_NAME_SPEC"));
        var business=List.of(fact("DD1","ORDER","100"),evidence("100","桶","SYSTEM","SYSTEM"));
        var line=page(review(normalize(source),normalize(business),normalize(business)),"SKU",1,50).rows().getFirst();
        assertThat(line.status()).isEqualTo("UNVERIFIED");
        assertThat(line.associationStatus()).isEqualTo("UNVERIFIED");
        assertThat(line.financialStatus()).isEqualTo("UNVERIFIED");
    }
    @Test void differentOriginalUnitsNeverProduceACombinedQuantity() {
        var header=fact("DD1","ORDER","100");
        var rows=normalize(List.of(header,evidence("50","桶","SYSTEM","SYSTEM"),evidence("50","箱","SYSTEM","SYSTEM")));
        var result=page(review(rows,rows,rows),"SKU",1,50).rows().getFirst();
        assertThat(result.source().quantity()).isNull();
        assertThat(result.quantityStatus()).isEqualTo("UNVERIFIED");
        assertThat(result.source().amount()).isEqualByComparingTo("100");
    }
    @Test void historicalJsonDoesNotInventNewEvidenceOrSubcheckResults() {
        var json=JsonMapper.builder().build();
        var row=json.readValue("""
                {"key":"SKU|DD1|NOODLE","kind":"SKU","status":"SNAPSHOT_MATCH","issues":[]}
                """,Row.class);
        assertThat(row.financialStatus()).isNull();
        assertThat(row.quantityStatus()).isNull();
        assertThat(row.associationStatus()).isNull();
        var fact=json.readValue("""
                {"key":"SKU|DD1|NOODLE","kind":"SKU","unit":"箱","uncertainties":[],"excludedRefund":false}
                """,Fact.class);
        assertThat(fact.unitEvidence()).isNull();
        assertThat(fact.associationEvidence()).isNull();
    }
    private static List<Fact> normalize(List<Fact> rows) {
        return BiReconciliationReviewService.normalizeUnits(rows,Map.of("BOX","箱","BUCKET","桶"));
    }
    private static Fact evidence(String amount,String unit,String unitEvidence,String associationEvidence) {
        var f=fact("DD1","SKU",amount);
        return new Fact(f.key(),f.orderNo(),f.kind(),f.city(),f.sales(),f.customer(),f.product(),f.specification(),unit,
                f.orderDate(),f.amount(),f.paid(),f.unpaid(),f.quantity(),f.updatedAt(),f.sourceRows(),false,List.of(),
                unit,null,unitEvidence,associationEvidence,null,null,null);
    }
}
