package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.BiReconciliationReview.*;
import com.rigour.order.api.v1.model.SalesOrderProductRepair.Evidence;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

class BiReconciliationRepairEvidenceTest {
    private final Instant now=Instant.parse("2026-09-14T00:00:00Z");
    private final ObjectMapper json=new ObjectMapper();
    private Version version(String capture) {
        return new Version(null,"online",null,null,now,"SUCCESS",1,
                new OnlineEvidence(capture,"sales-source","sales",null,now,now,true,false,1,1,false));
    }
    private Fact fact(String variant) {
        return new Fact("SKU|DD1|SKU1","DD1","SKU","北京","销售","客户","面","12桶/箱","BUCKET",now,
                new BigDecimal("78.125"),null,null,new BigDecimal("12"),now,null,false,List.of(),"BUCKET",null,
                "SYSTEM","SYSTEM",null,null,null,"11",variant,null);
    }
    private Evidence evidence() {
        return json.readValue("""
            {"lineId":11,"productVariantId":22,"sourceOrderNo":"DD1","sourceNamespace":"sales-source",
             "sourceCaptureRef":"capture-1","sourceProductRecordId":"rec-product","sourceProductCode":"P1",
             "sourceIdentityStatus":"OPERATOR_CONFIRMED","transactionUnitStatus":"OPERATOR_CONFIRMED",
             "historicalTransactionUnitCode":"BUCKET","storedUnitCode":"BUCKET","transactionQuantity":12}
            """,Evidence.class);
    }
    @Test void exactCaptureAndCurrentLineKeepFinancialsAndRawQuantityUnchanged() {
        var original=fact("22");
        var result=BiReconciliationRepairEvidence.apply(List.of(original),List.of(evidence()),version("capture-1")).getFirst();
        assertThat(result.amount()).isEqualTo(original.amount());
        assertThat(result.quantity()).isEqualTo(original.quantity());
        assertThat(result.rawUnit()).isEqualTo("BUCKET");
        assertThat(result.unitEvidence()).isEqualTo("OPERATOR_CONFIRMED");
        assertThat(result.associationEvidence()).isEqualTo("OPERATOR_CONFIRMED");
        assertThat(result.sourceProductId()).isEqualTo("rec-product");
        var normalized=BiReconciliationReviewService.normalizeUnits(List.of(result),Map.of("BUCKET","桶")).getFirst();
        assertThat(normalized.unit()).isEqualTo("桶");
        assertThat(normalized.rawUnit()).isEqualTo("BUCKET");
        assertThat(normalized.confirmedUnitCode()).isEqualTo("BUCKET");
    }
    @Test void anotherCaptureOrStaleBiVariantCannotReuseConfirmation() {
        var original=fact("22");
        assertThat(BiReconciliationRepairEvidence.apply(List.of(original),List.of(evidence()),version("capture-2")))
                .containsExactly(original);
        var stale=fact("21");
        assertThat(BiReconciliationRepairEvidence.apply(List.of(stale),List.of(evidence()),version("capture-1")))
                .containsExactly(stale);
    }
    @Test void duplicateEvidenceNeverSelectsAnArbitraryRepair() {
        var original=fact("22");
        assertThat(BiReconciliationRepairEvidence.apply(List.of(original),List.of(evidence(),evidence()),version("capture-1")))
                .containsExactly(original);
    }

    @Test void sameOrderSkuAndSharedKeysCannotSpreadOneLineConfirmation() {
        var first = fact("22");
        for (var second : List.of(change(first, Map.of("systemLineId", "12")),
                change(first, Map.of("systemLineId", "12", "key", "SKU|DD1|OLD-SKU")),
                change(first, Map.of("systemLineId", "12", "systemVariantId", "23")))) {
            var result = BiReconciliationRepairEvidence.apply(List.of(first, second), List.of(evidence()), version("capture-1"));
            assertThat(result).containsExactly(first, second);
            var source = new BiReconciliationSourceNormalizer(json).normalize(sourceRows(null), result).getFirst();
            assertThat(source.associationEvidence()).isNotEqualTo("OPERATOR_CONFIRMED");
            assertThat(source.confirmedUnitCode()).isNull();
        }
    }

    @Test void explicitSourceUnitConflictSurvivesNormalizationAndMoneyIsNeverConverted() {
        var original = fact("22");
        var catalog = BiReconciliationRepairEvidence.apply(List.of(original), List.of(evidence()), version("capture-1"));
        var source = new BiReconciliationSourceNormalizer(json).normalize(sourceRows("箱"), catalog).getFirst();
        var normalized = BiReconciliationReviewService.normalizeUnits(List.of(source, catalog.getFirst()),
                Map.of("BUCKET", "桶", "BOX", "箱"));
        assertThat(normalized.getFirst().unitEvidence()).isEqualTo("EXPLICIT");
        assertThat(normalized.getFirst().confirmedUnitCode()).isNull();
        assertThat(normalized.getFirst().unitCode()).isEqualTo("BOX");
        assertThat(normalized.getLast().unitCode()).isEqualTo("BUCKET");
        assertThat(normalized).allSatisfy(f -> {
            assertThat(f.quantity()).isEqualByComparingTo("12");
            assertThat(f.amount()).isEqualByComparingTo("78.125");
        });
        var explicit = change(original, Map.of("unitEvidence", "EXPLICIT"));
        var conflicting = change(evidence(), Map.of("historicalTransactionUnitCode", "BOX"));
        var untouchedUnit = BiReconciliationRepairEvidence.apply(List.of(explicit), List.of(conflicting), version("capture-1")).getFirst();
        assertThat(untouchedUnit.unitEvidence()).isEqualTo("EXPLICIT");
        assertThat(untouchedUnit.confirmedUnitCode()).isNull();
    }

    @Test void wrongNamespaceFileBatchAndChangedIdentityOrFactsNeverReuseEvidence() {
        var original = fact("22");
        var file = new Version("file-batch", "file", null, null, now, "SUCCESS", 1, null);
        assertThat(BiReconciliationRepairEvidence.apply(List.of(original), List.of(evidence()), file)).containsExactly(original);
        for (var changed : List.of(change(evidence(), Map.of("sourceNamespace", "other-source")),
                change(evidence(), Map.of("sourceOrderNo", "DD2")),
                change(evidence(), Map.of("storedUnitCode", "BOX")),
                change(evidence(), Map.of("transactionQuantity", 13)),
                change(evidence(), Map.of("sourceIdentityStatus", "UNVERIFIED")))) {
            assertThat(BiReconciliationRepairEvidence.apply(List.of(original), List.of(changed), version("capture-1")))
                    .containsExactly(original);
        }
        for (var changed : List.of(change(original, Map.of("sourceProductId", "other-record")),
                change(original, Map.of("sourceProductCode", "OTHER")),
                change(original, Map.of("excludedRefund", true)),
                change(original, Map.of("quantity", BigDecimal.ZERO)),
                change(original, Map.of("quantity", BigDecimal.ONE.negate())))) {
            assertThat(BiReconciliationRepairEvidence.apply(List.of(changed), List.of(evidence()), version("capture-1")))
                    .containsExactly(changed);
        }
    }

    @Test void currentFileBatchRequiresBothReferencesAndMissingVersionCannotConfirm() {
        var original = fact("22");
        var fileEvidence = change(evidence(), Map.of("sourceNamespace", "file-batch", "sourceCaptureRef", "file-batch"));
        var file = new Version("file-batch", "file", null, null, now, "SUCCESS", 1, null);
        assertThat(BiReconciliationRepairEvidence.apply(List.of(original), List.of(fileEvidence), file).getFirst()
                .associationEvidence()).isEqualTo("OPERATOR_CONFIRMED");
        var unknown = new Version(null, "file", null, null, now, "SUCCESS", 1, null);
        var values = new LinkedHashMap<String, Object>();
        values.put("sourceNamespace", null); values.put("sourceCaptureRef", null);
        assertThat(BiReconciliationRepairEvidence.apply(List.of(original), List.of(change(evidence(), values)), unknown))
                .containsExactly(original);
    }

    @Test void multipleSourceRowsCannotReuseOneConfirmedTransactionEvenWhenQuantitiesSumExactly() {
        var catalog=BiReconciliationRepairEvidence.apply(List.of(fact("22")),List.of(evidence()),version("capture-1"));
        var normalizer=new BiReconciliationSourceNormalizer(json);
        for (int quantity:List.of(6,12)) {
            var rows=List.of(sourceRow("row-1",quantity,null),sourceRow("row-2",quantity,null));
            var result=normalizer.normalize(rows,catalog);
            assertThat(result).hasSize(2).allSatisfy(f->{
                assertThat(f.associationEvidence()).isNotEqualTo("OPERATOR_CONFIRMED");
                assertThat(f.confirmedUnitCode()).isNull();
                assertThat(f.unitEvidence()).isEqualTo("COLUMN_INFERRED");
                assertThat(f.rawUnit()).isEqualTo("箱");
                assertThat(f.quantity()).isEqualByComparingTo(Integer.toString(quantity));
                assertThat(f.amount()).isEqualByComparingTo("78.125");
            });
            assertThat(result).extracting(Fact::sourceRecordId).containsExactly("row-1","row-2");
        }
    }

    @Test void uniqueSourceRowMayConfirmOnlyItsExactQuantityAndRepeatedLayerIsNotAnotherTransaction() {
        var confirmed=BiReconciliationRepairEvidence.apply(List.of(fact("22")),List.of(evidence()),version("capture-1")).getFirst();
        var normalizer=new BiReconciliationSourceNormalizer(json);
        var exact=normalizer.normalize(List.of(sourceRow("one-row",12,null)),List.of(confirmed,confirmed)).getFirst();
        assertThat(exact.confirmedUnitCode()).isEqualTo("BUCKET");
        assertThat(exact.rawUnit()).isEqualTo("箱");
        assertThat(exact.quantity()).isEqualByComparingTo("12");
        var changed=normalizer.normalize(List.of(sourceRow("one-row",6,null)),List.of(confirmed,confirmed)).getFirst();
        assertThat(changed.confirmedUnitCode()).isNull();
        assertThat(changed.unitEvidence()).isEqualTo("COLUMN_INFERRED");
        assertThat(changed.quantity()).isEqualByComparingTo("6");
    }

    @Test void incompleteBiLayerCannotHideAnotherBusinessLineOfSameSku() {
        var confirmed=BiReconciliationRepairEvidence.apply(List.of(fact("22")),List.of(evidence()),version("capture-1")).getFirst();
        for (String key:List.of(confirmed.key(),"SKU|DD1|OLD-SKU")) {
            var sibling=change(fact("22"),Map.of("systemLineId","12","key",key));
            var source=new BiReconciliationSourceNormalizer(json).normalize(sourceRows(null),List.of(confirmed,sibling)).getFirst();
            assertThat(source.associationEvidence()).isNotEqualTo("OPERATOR_CONFIRMED");
            assertThat(source.confirmedUnitCode()).isNull();
        }
    }

    @Test void duplicateSourceProductAcrossDifferentSkuKeysStillCannotInheritUnit() {
        var first=BiReconciliationRepairEvidence.apply(List.of(fact("22")),List.of(evidence()),version("capture-1")).getFirst();
        var second=change(first,Map.of("key","SKU|DD1|SKU2","systemLineId","12","systemVariantId","23"));
        var rows=List.of(sourceRow("first",12,"SKU1"),sourceRow("second",12,"SKU2"));
        assertThat(new BiReconciliationSourceNormalizer(json).normalize(rows,List.of(first,second)))
                .allSatisfy(f->assertThat(f.confirmedUnitCode()).isNull());
    }

    private Map<String,Object> sourceRow(String recordId,int quantity,String sku) {
        var row=new LinkedHashMap<>(sourceRows(null).getFirst());
        var fields=(tools.jackson.databind.node.ObjectNode) json.readTree(row.get("valuesJson").toString());
        fields.put("数量(箱)",quantity);
        if (sku!=null) fields.put("SKU编码",sku);
        row.put("recordId",recordId); row.put("valuesJson",json.writeValueAsString(fields));
        return row;
    }

    private List<Map<String, Object>> sourceRows(String explicitUnit) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("订单编号", "DD1"); fields.put("产品编码", "P1"); fields.put("数量(箱)", 12);
        fields.put("实际小计", new BigDecimal("78.125"));
        if (explicitUnit != null) fields.put("单位", explicitUnit);
        return List.of(Map.of("tableCode", "FEISHU_SALES_ORDER_LINE", "recordId", "source-line-1",
                "sheetName", "明细", "valuesJson", json.writeValueAsString(fields)));
    }

    private <T> T change(T original, Map<String, Object> changes) {
        var node = (tools.jackson.databind.node.ObjectNode) json.valueToTree(original);
        changes.forEach((key, value) -> node.set(key, json.valueToTree(value)));
        @SuppressWarnings("unchecked") Class<T> type = (Class<T>) original.getClass();
        return json.treeToValue(node, type);
    }
}
