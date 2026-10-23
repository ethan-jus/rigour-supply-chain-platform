package com.rigour.analytics.infrastructure.persistence.scope;

import com.rigour.analytics.domain.cost.CostFact;

import java.util.*;

/** 同批次原费用承担成本，报销只保留支付来源；失配进入待核对而不是静默再计一次。 */
public final class CostReimbursementReconciler {
    private CostReimbursementReconciler() {}

    public static CostFact reconcile(CostFact fact, Map<String, CostFact> originals) {
        var link = fact.dimensions().get("relatedCost");
        if (link == null) return fact;
        var original = originals.get(link.key());
        boolean match =
                original != null
                        && original.confirmed()
                        && Long.toString(original.revision()).equals(link.label())
                        && original.sourceCategory().equals(fact.dimension("relatedCategory").key())
                        && original.category().equals(fact.category())
                        && Objects.equals(original.rawCity(), fact.rawCity())
                        && original.costMonth().equals(fact.costMonth())
                        && fact.amount() != null
                        && original.amount().compareTo(fact.amount()) == 0;
        var dims = new LinkedHashMap<>(fact.dimensions());
        dims.put(
                "costTreatment",
                new CostFact.Dimension(
                        match ? "LINKED" : "REVIEW",
                        match ? "已由原费用计入，不重复汇总" : "原费用已变化或不可核对，暂不计入",
                        false));
        return fact.withDimensions(dims);
    }
}
