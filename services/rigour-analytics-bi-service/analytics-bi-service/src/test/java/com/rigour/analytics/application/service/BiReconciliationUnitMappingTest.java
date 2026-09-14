package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.BiReconciliationReview.Fact;
import com.rigour.analytics.infrastructure.persistence.mapper.BiReconciliationReviewMapper;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static com.rigour.analytics.application.service.BiReconciliationReviewServiceTest.facts;
import static com.rigour.analytics.application.service.BiReconciliationReviewServiceTest.review;
import static org.assertj.core.api.Assertions.assertThat;

class BiReconciliationUnitMappingTest {
    private static final Map<String,String> DICTIONARY=Map.of("BUCKET","桶","BOX","箱");
    @Test void rawBucketCodeIsPreservedWhileChineseUnitUsesApiLabel() {
        var original=withUnit("BUCKET");
        var mapped=BiReconciliationReviewService.normalizeUnits(original,DICTIONARY);
        assertThat(mapped.get(1).unit()).isEqualTo("桶");
        assertThat(mapped.get(1).rawUnit()).isEqualTo("BUCKET");
        assertThat(mapped.get(1).unitCode()).isEqualTo("BUCKET");
        assertThat(original.get(1).unit()).isEqualTo("BUCKET");
        var source=BiReconciliationReviewService.normalizeUnits(withUnit("桶"),DICTIONARY);
        assertThat(review(source,mapped,mapped).status()).isEqualTo("SNAPSHOT_MATCH");
        assertThat(source.get(1).rawUnit()).isEqualTo("桶");
        var json=JsonMapper.builder().build();
        assertThat(json.readValue(json.writeValueAsString(mapped.get(1)),Fact.class)).isEqualTo(mapped.get(1));
    }
    @Test void sourceBoxesAndBusinessBucketsAreNotMappedEqualDespiteMatchingOrderMoney() {
        var source=BiReconciliationReviewService.normalizeUnits(withUnit("箱"),DICTIONARY);
        var business=BiReconciliationReviewService.normalizeUnits(withUnit("BUCKET"),DICTIONARY);
        var result=review(source,business,business);
        assertThat(result.status()).isEqualTo("DIFF");
        assertThat(result.rows()).filteredOn(row->"SKU".equals(row.kind())).singleElement()
                .satisfies(row->assertThat(row.issues()).anyMatch(issue->issue.contains("原单位不同，未自动换算")));
        assertThat(source.get(1).quantity()).isEqualByComparingTo(business.get(1).quantity());
    }
    @Test void unknownUnitAndDuplicateDictionaryLabelsRemainUnverifiedEvenWhenAmountsMatch() {
        for (var dictionary:List.of(Map.<String,String>of(),Map.of("OTHER_A","未知","OTHER_B","未知"))) {
            var rows=BiReconciliationReviewService.normalizeUnits(withUnit("未知"),dictionary);
            assertThat(rows.get(1).unitCode()).isNull();
            assertThat(rows.get(1).rawUnit()).isEqualTo("未知");
            assertThat(review(rows,rows,rows).status()).isEqualTo("UNVERIFIED");
        }
    }
    @Test void distinctCodesWithSameDisplayLabelCannotCollapseIntoOneUnit() {
        var dictionary=Map.of("PACKAGE_A","包","PACKAGE_B","包");
        var source=BiReconciliationReviewService.normalizeUnits(withUnit("PACKAGE_A"),dictionary);
        var business=BiReconciliationReviewService.normalizeUnits(withUnit("PACKAGE_B"),dictionary);
        assertThat(source.get(1).unit()).isEqualTo(business.get(1).unit());
        assertThat(review(source,business,business).status()).isEqualTo("DIFF");
    }
    @Test void sourceQueriesKeepRawUnitAndNeverReadSettingsSchema() {
        for (var method:BiReconciliationReviewMapper.class.getDeclaredMethods()) {
            if (!List.of("businessLines","biLines").contains(method.getName())) continue;
            var sql=String.join("\n",method.getAnnotation(Select.class).value());
            assertThat(sql).contains("l.unit_code AS unit").doesNotContain("rigour_settings", "data_dictionary_item", "u.dictionary");
        }
    }
    private static List<Fact> withUnit(String unit) {
        var values=facts("DD1","100");
        var f=values.get(1);
        return List.of(values.getFirst(),new Fact(f.key(),f.orderNo(),f.kind(),f.city(),f.sales(),f.customer(),f.product(),f.specification(),
                unit,f.orderDate(),f.amount(),f.paid(),f.unpaid(),f.quantity(),f.updatedAt(),f.sourceRows(),f.excludedRefund(),f.uncertainties()));
    }
}
