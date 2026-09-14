package com.rigour.analytics.application.service;

import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class BiReconciliationSourceNormalizerTest {
    private final BiReconciliationSourceNormalizer normalizer=new BiReconciliationSourceNormalizer(JsonMapper.builder().build());
    @Test void missingMoneyIsNotReplacedWithCollectionOrListPrice() {
        var rows=normalizer.normalize(List.of(raw("FEISHU_SALES_ORDER","DD1","{\"销售日期\":\"2026-09-01\",\"收款合计\":\"100\",\"小计\":\"200\",\"数量\":\"1\"}")),List.of());
        assertThat(rows.getFirst().amount()).isNull();
        assertThat(rows.getFirst().paid()).isEqualByComparingTo("100");
        assertThat(rows.getFirst().excludedRefund()).isFalse();
    }
    @Test void explicitZeroQuantityIsExcludedButMissingQuantityIsNot() {
        var zero=normalizer.normalize(List.of(raw("FEISHU_SALES_ORDER","DD1","{\"数量\":\"0\"}")),List.of()).getFirst();
        var missing=normalizer.normalize(List.of(raw("FEISHU_SALES_ORDER","DD1","{}")),List.of()).getFirst();
        assertThat(zero.excludedRefund()).isTrue();
        assertThat(missing.excludedRefund()).isFalse();
    }
    @Test void skuAssociatesOnlyWithUniqueExactProductAndPreservesSourceUnit() {
        var rows=normalizer.normalize(List.of(raw("FEISHU_SALES_ORDER","DD1","{\"销售日期\":\"2026-09-01\",\"数量\":\"1\"}"),
                raw("FEISHU_SALES_ORDER_LINE","ODD1","{\"关联订单\":\"DD1 客户\",\"订单产品\":\"方便面\",\"数量(箱)\":\"2\",\"实际小计\":\"100\"}")),
                BiReconciliationReviewServiceTest.facts("DD1","100"));
        assertThat(rows.get(1).key()).isEqualTo("SKU|DD1|NOODLE");
        assertThat(rows.get(1).unit()).isEqualTo("箱");
        assertThat(rows.get(1).unitEvidence()).isEqualTo("COLUMN_INFERRED");
        assertThat(rows.get(1).associationEvidence()).isEqualTo("EXACT_NAME_SPEC");
        assertThat(rows.get(1).orderDate()).isEqualTo(Instant.parse("2026-08-31T16:00:00Z"));
    }
    @Test void unlinkedSkuStaysUnverifiedRatherThanMatchingByAmount() {
        var rows=normalizer.normalize(List.of(raw("FEISHU_SALES_ORDER_LINE","ODD1","{\"关联订单\":\"DD1\",\"订单产品\":\"未知口味\",\"实际小计\":\"100\"}")),
                BiReconciliationReviewServiceTest.facts("DD1","100"));
        assertThat(rows.getFirst().key()).startsWith("UNLINKED|");
        assertThat(rows.getFirst().uncertainties()).anyMatch(x->x.contains("唯一关联"));
    }
    @Test void actualFeishuProductDescriptorUsesNameAndSpecificationNotAmount() {
        var f=BiReconciliationReviewServiceTest.fact("DD1","SKU","100");
        var catalog=new com.rigour.analytics.api.v1.model.BiReconciliationReview.Fact(f.key(),f.orderNo(),f.kind(),f.city(),f.sales(),f.customer(),
                "金汤肥牛","12桶/箱","桶",f.orderDate(),f.amount(),null,null,f.quantity(),f.updatedAt(),null,false,List.of());
        var rows=normalizer.normalize(List.of(raw("FEISHU_SALES_ORDER_LINE","ODD1","{\"关联订单\":\"DD1\",\"产品编号\":\"杨掌柜-金汤肥牛-12桶/箱\",\"数量(箱)\":\"1\",\"实际小计\":\"78\"}")),List.of(catalog));
        assertThat(rows.getFirst().key()).isEqualTo(f.key());
        assertThat(rows.getFirst().unit()).isEqualTo("箱");
        assertThat(rows.getFirst().product()).isEqualTo("杨掌柜-金汤肥牛-12桶/箱");
    }
    @Test void dateOnlyExcelIntegerAndOnlineEpochUseTheSameShanghaiBusinessDay() {
        Instant expected=Instant.parse("2026-08-31T16:00:00Z");
        long serial=ChronoUnit.DAYS.between(LocalDate.of(1899,12,30),LocalDate.of(2026,9,1));
        for (String value:List.of("2026-09-01","2026/9/1",Long.toString(serial),Long.toString(expected.toEpochMilli()),"2026-09-01 00:00:00")) {
            assertThat(BiReconciliationSourceNormalizer.date(value)).as(value).isEqualTo(expected);
        }
        assertThat(BiReconciliationSourceNormalizer.date(serial+".5")).isEqualTo(expected.plusSeconds(43200));
        assertThat(BiReconciliationSourceNormalizer.date("2026-02-30")).isNull();
        assertThat(BiReconciliationSourceNormalizer.date("1790000000")).isNull();
        assertThat(BiReconciliationSourceNormalizer.date("1790000000000.5")).isNull();
    }
    @Test void richTextFormulaAndLookupValuesAreReadWithoutUsingRecordIdsAsNames() {
        var row=online("FEISHU_SALES_ORDER","recHeader", """
                {"订单编号门店":[{"text":"DD","type":"text"},{"text":"1 客户","type":"text"}],
                 "销售日期":1788192000000,"城市":{"type":3,"value":["北京"]},
                 "销售":{"type":11,"value":[{"name":"销售甲","id":"ouSecret","email":"sales@example.test"}]},
                 "门店":[{"text":"客"},{"text":"户"}],"数量":{"type":2,"value":[1]},
                 "实际小计":{"type":20,"value":[100]},"收款合计":{"value":[0]},"待付金额":{"value":[100]}}
                """);
        var fact=normalizer.normalize(List.of(row),List.of()).getFirst();
        assertThat(fact.orderNo()).isEqualTo("DD1");
        assertThat(fact.customer()).isEqualTo("客户");
        assertThat(fact.sales()).isEqualTo("销售甲");
        assertThat(fact.city()).isEqualTo("北京");
        assertThat(fact.amount()).isEqualByComparingTo("100");
        assertThat(fact.quantity()).isEqualByComparingTo("1");
        assertThat(fact.sourceRows()).contains("recHeader").doesNotContain("ouSecret");
        assertThat(fact.uncertainties()).isEmpty();
    }
    @Test void linkedRecordIdsWithoutReadableValuesStayUnknownAndDoNotMatchOrderNumbers() {
        var fact=normalizer.normalize(List.of(online("FEISHU_SALES_ORDER","recHeader", """
                {"订单编号门店":{"record_ids":["recOrder"]},"门店":{"link_record_ids":["recCustomer"]},
                 "销售":{"id":"ouSales"},"城市":{"value":[{"id":"recCity"}]},
                 "实际小计":{"value":[1,2]},"数量":{"value":[0,1]}}
                """)),List.of()).getFirst();
        assertThat(fact.orderNo()).isNull();
        assertThat(fact.customer()).isNull();
        assertThat(fact.city()).isNull();
        assertThat(fact.sales()).isNull();
        assertThat(fact.amount()).isNull();
        assertThat(fact.quantity()).isNull();
        assertThat(fact.excludedRefund()).isFalse();
        assertThat(fact.key()).endsWith("recHeader");
    }
    @Test void exactCapturedOrderAndProductReferencesResolveWithoutBorrowingBusinessAmounts() {
        var header=online("FEISHU_SALES_ORDER","recHeader", """
                {"订单编号门店":[{"text":"DD1 客户"}],"销售日期":1788192000000,
                 "城市":"北京","销售":"销售甲","门店":"客户","数量":1,
                 "实际小计":78,"收款合计":0,"待付金额":78}
                """);
        var line=online("FEISHU_SALES_ORDER_LINE","recLine", """
                {"关联订单":{"link_record_ids":["recHeader"]},"产品编号":{"link_record_ids":["recProduct"]},
                 "数量":1,"实际小计":78,"单位":"桶"}
                """);
        var product=online("FEISHU_PRODUCT","recProduct", """
                {"产品名称":[{"text":"金汤肥牛"}],"规格":[{"text":"12桶/箱"}],
                 "产品编码":"PRODUCT1","产品编码名称":{"type":20,"value":[{"text":"杨掌柜-金汤肥牛-12桶/箱"}]}}
                """);
        var f=BiReconciliationReviewServiceTest.fact("DD1","SKU","999");
        var catalog=new com.rigour.analytics.api.v1.model.BiReconciliationReview.Fact(f.key(),f.orderNo(),f.kind(),f.city(),f.sales(),f.customer(),
                "金汤肥牛","12桶/箱","桶",f.orderDate(),f.amount(),null,null,f.quantity(),f.updatedAt(),null,false,List.of());
        var facts=normalizer.normalize(List.of(line,product,header),List.of(catalog));
        assertThat(facts).hasSize(2);
        var result=facts.get(1);
        assertThat(result.key()).isEqualTo(catalog.key());
        assertThat(result.orderNo()).isEqualTo("DD1");
        assertThat(result.product()).isEqualTo("金汤肥牛");
        assertThat(result.specification()).isEqualTo("12桶/箱");
        assertThat(result.amount()).isEqualByComparingTo("78");
        assertThat(result.paid()).isNull();
        assertThat(result.associationEvidence()).isEqualTo("EXACT_NAME_SPEC");
        assertThat(result.sourceProductId()).isEqualTo("recProduct");
        assertThat(result.sourceProductCode()).isEqualTo("PRODUCT1");
        assertThat(result.sourceRecordId()).isEqualTo("recLine");
        assertThat(result.uncertainties()).anyMatch(x->x.contains("仅定位候选"));
        var withoutProduct=normalizer.normalize(List.of(header,line),List.of(catalog)).get(1);
        assertThat(withoutProduct.product()).isNull();
        assertThat(withoutProduct.key()).startsWith("UNLINKED|");
        var withoutHeader=normalizer.normalize(List.of(line,product),List.of(catalog)).getFirst();
        assertThat(withoutHeader.orderNo()).isNull();
        assertThat(withoutHeader.orderDate()).isNull();
    }
    @Test void ambiguousReferencesAndUnknownUnitsRemainUnverified() {
        var header=online("FEISHU_SALES_ORDER","recHeader","{\"订单编号\":\"DD1\",\"数量\":1}");
        var line=online("FEISHU_SALES_ORDER_LINE","recLine", """
                {"关联订单":{"link_record_ids":["recHeader","recOther"]},"产品编号":{"link_record_ids":["recProduct"]},"数量":1}
                """);
        var fact=normalizer.normalize(List.of(header,line),BiReconciliationReviewServiceTest.facts("DD1","1")).get(1);
        assertThat(fact.orderNo()).isNull();
        assertThat(fact.unit()).isNull();
        assertThat(fact.uncertainties()).anyMatch(x->x.contains("数量单位"));
    }
    @Test void metadataModificationTimeNeverBecomesBusinessDate() {
        var raw=new java.util.LinkedHashMap<>(online("FEISHU_SALES_ORDER","recHeader","{\"订单编号\":\"DD1\",\"数量\":1}"));
        long epoch=Instant.parse("2026-09-01T00:00:00Z").toEpochMilli();
        raw.put("createdTime",epoch); raw.put("lastModifiedTime",epoch+1000);
        var fact=normalizer.normalize(List.of(raw),List.of()).getFirst();
        assertThat(fact.orderDate()).isNull();
        assertThat(fact.updatedAt()).isEqualTo(Instant.ofEpochMilli(epoch+1000));
        assertThat(fact.uncertainties()).anyMatch(x->x.contains("业务日期缺失"));
    }
    @Test void explicitUnitWinsOverColumnHintWithoutChangingQuantityOrMoney() {
        var fact=normalizer.normalize(List.of(raw("FEISHU_SALES_ORDER_LINE","ODD1", """
                {"关联订单":"DD1","SKU编码":"NOODLE","数量(箱)":2,"实际小计":156,"单位":"桶"}
                """)),BiReconciliationReviewServiceTest.facts("DD1","156")).getFirst();
        assertThat(fact.unit()).isEqualTo("桶");
        assertThat(fact.unitEvidence()).isEqualTo("EXPLICIT");
        assertThat(fact.associationEvidence()).isEqualTo("SOURCE_CODE");
        assertThat(fact.quantity()).isEqualByComparingTo("2");
        assertThat(fact.amount()).isEqualByComparingTo("156");
    }
    @Test void genericQuantityCannotBorrowAnUnselectedColumnUnit() {
        var fact=normalizer.normalize(List.of(raw("FEISHU_SALES_ORDER_LINE","ODD1", """
                {"关联订单":"DD1","数量":24,"数量(箱)":2,"实际小计":156}
                """)),List.of()).getFirst();
        assertThat(fact.unit()).isNull();
        assertThat(fact.unitEvidence()).isEqualTo("UNKNOWN");
        assertThat(fact.quantity()).isEqualByComparingTo("24");
    }
    @Test void conflictingExplicitSkuNeverFallsBackToName() {
        var fact=normalizer.normalize(List.of(raw("FEISHU_SALES_ORDER_LINE","ODD1", """
                {"关联订单":"DD1","SKU编码":"OTHER","订单产品":"方便面","数量":1,"实际小计":100,"单位":"箱"}
                """)),BiReconciliationReviewServiceTest.facts("DD1","100")).getFirst();
        assertThat(fact.key()).startsWith("UNLINKED|");
    }
    @Test void uniqueSourceProductReferenceLinksEvenWhenDisplayNameChanges() {
        var f=BiReconciliationReviewServiceTest.fact("DD1","SKU","100");
        var catalog=new com.rigour.analytics.api.v1.model.BiReconciliationReview.Fact(f.key(),f.orderNo(),f.kind(),f.city(),f.sales(),f.customer(),
                "新名称",null,"桶",f.orderDate(),f.amount(),null,null,f.quantity(),f.updatedAt(),null,false,List.of(),
                "BUCKET","BUCKET","SYSTEM","SYSTEM",null,"recProduct","PRODUCT1");
        var fact=normalizer.normalize(List.of(online("FEISHU_SALES_ORDER_LINE","recLine", """
                {"关联订单":"DD1","产品编号":{"link_record_ids":["recProduct"]},"订单产品":"旧名称","数量":1,"实际小计":100,"单位":"桶"}
                """)),List.of(catalog)).getFirst();
        assertThat(fact.key()).isEqualTo(catalog.key());
        assertThat(fact.associationEvidence()).isEqualTo("SOURCE_RECORD");
        assertThat(fact.product()).isEqualTo("旧名称");
    }
    static Map<String,Object> online(String table,String recordId,String json) {
        return Map.of("tableCode",table,"recordId",recordId,"valuesJson",json,"sheetName",table,"rowNumber",1);
    }
    static Map<String,Object> raw(String table,String no,String json) {
        return Map.of("tableCode",table,"sourceDocumentNo",no,"valuesJson",json,"sheetName","订单","rowNumber",2);
    }
}
