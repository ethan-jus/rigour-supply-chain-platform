package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.BiReconciliationReview.Fact;
import com.rigour.analytics.api.v1.model.BiReconciliationReview.Version;
import com.rigour.order.api.v1.model.SalesOrderProductRepair.Evidence;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** 仅将人工证据用于同次采集的唯一交易行，不改量价，也不把逐行确认传播到重复SKU。 */
final class BiReconciliationRepairEvidence {
    private BiReconciliationRepairEvidence() { }

    static List<Fact> apply(List<Fact> facts, List<Evidence> evidence, Version version) {
        String namespace = version.onlineEvidence() == null ? version.batchId() : version.onlineEvidence().sourceId();
        String capture = version.onlineEvidence() == null ? version.batchId() : version.onlineEvidence().captureId();
        if (!present(namespace) || !present(capture)) return facts;
        var skuFacts = facts.stream().filter(f -> "SKU".equals(f.kind())).toList();
        // 来源标准化会按汇总键复用确认；多行同SKU或同键时，现有证据不足以唯一定位来源交易行。
        var keyCounts = skuFacts.stream().collect(Collectors.groupingBy(f -> Objects.toString(f.key(), ""), Collectors.counting()));
        var variantCounts = skuFacts.stream().collect(Collectors.groupingBy(
                f -> new LineIdentity(f.orderNo(), f.systemVariantId()), Collectors.counting()));
        var byLine=evidence.stream().filter(e->e.lineId()!=null && Objects.equals(namespace,e.sourceNamespace())
                && Objects.equals(capture,e.sourceCaptureRef()))
                .collect(Collectors.groupingBy(e->e.lineId().toString()));
        return facts.stream().map(f -> {
            if (!"SKU".equals(f.kind()) || !present(f.orderNo()) || !present(f.systemLineId())
                    || !present(f.systemVariantId()) || f.excludedRefund()
                    || f.quantity() == null || f.quantity().signum() <= 0
                    || keyCounts.getOrDefault(Objects.toString(f.key(), ""), 0L) != 1
                    || variantCounts.getOrDefault(new LineIdentity(f.orderNo(), f.systemVariantId()), 0L) != 1) return f;
            var matches = byLine.getOrDefault(f.systemLineId(),List.of()).stream().filter(e -> Objects.equals(f.orderNo(), e.sourceOrderNo())
                    && f.systemLineId().equals(Objects.toString(e.lineId(), ""))
                    && f.systemVariantId().equals(Objects.toString(e.productVariantId(), ""))
                    && Objects.equals(f.rawUnit(), e.storedUnitCode())
                    && f.quantity() != null && e.transactionQuantity() != null
                    && f.quantity().compareTo(e.transactionQuantity()) == 0
                    && (present(e.sourceProductRecordId()) || present(e.sourceProductCode()))
                    && compatible(f.sourceProductId(), e.sourceProductRecordId())
                    && compatible(f.sourceProductCode(), e.sourceProductCode())
                    && "OPERATOR_CONFIRMED".equals(e.sourceIdentityStatus())).toList();
            if (matches.size() != 1) return f;
            var e = matches.getFirst();
            String confirmed = "OPERATOR_CONFIRMED".equals(e.transactionUnitStatus())
                    && !"EXPLICIT".equals(f.unitEvidence()) && present(e.historicalTransactionUnitCode())
                    ? e.historicalTransactionUnitCode() : null;
            return new Fact(f.key(), f.orderNo(), f.kind(), f.city(), f.sales(), f.customer(), f.product(),
                    f.specification(), f.unit(), f.orderDate(), f.amount(), f.paid(), f.unpaid(), f.quantity(),
                    f.updatedAt(), f.sourceRows(), f.excludedRefund(), f.uncertainties(), f.rawUnit(), f.unitCode(),
                    confirmed == null ? f.unitEvidence() : "OPERATOR_CONFIRMED", "OPERATOR_CONFIRMED",
                    f.sourceRecordId(), e.sourceProductRecordId(), e.sourceProductCode(), f.systemLineId(),
                    f.systemVariantId(), confirmed);
        }).toList();
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }
    private static boolean compatible(String original, String proposed) {
        return !present(original) || !present(proposed) || original.equals(proposed);
    }
    private record LineIdentity(String orderNo, String variantId) { }
}
