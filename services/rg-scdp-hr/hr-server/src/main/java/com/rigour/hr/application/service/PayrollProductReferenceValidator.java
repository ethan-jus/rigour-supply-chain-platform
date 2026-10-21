package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.PayrollSchemeCommissionPolicy;
import com.rigour.hr.application.port.out.HrPayrollSource;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import java.util.*;

/** Validates identifiers against the owning ERP service before a rule becomes effective. */
final class PayrollProductReferenceValidator {
    private PayrollProductReferenceValidator() {}

    static void validate(
            HrPayrollSource source, String tenant, PayrollSchemeCommissionPolicy policy) {
        if (policy.rules().isEmpty()) return;
        var categories =
                source.productCategories(tenant).stream()
                        .map(com.rigour.erp.api.v1.model.ErpPayrollCategoryView::id)
                        .collect(java.util.stream.Collectors.toSet());
        var variants =
                policy.rules().stream()
                        .flatMap(
                                r ->
                                        java.util.stream.Stream.concat(
                                                r.variantIds().stream(),
                                                r.excludedVariantIds().stream()))
                        .distinct()
                        .toList();
        Set<Long> found = new HashSet<>();
        for (int i = 0; i < variants.size(); i += 100)
            source.productsByVariants(
                            tenant, variants.subList(i, Math.min(i + 100, variants.size())))
                    .forEach(p -> found.add(p.productVariantId()));
        if (!found.containsAll(variants)) throw bad("部分商品规格已停用或不存在，请重新选择");
        Set<String> units =
                policy.rules().stream()
                                .anyMatch(r -> !r.excluded() && "QUANTITY".equals(r.method()))
                        ? source.dictionary(tenant, "PRODUCT_UNIT")
                        : Set.of();
        for (var rule : policy.rules()) {
            if (!categories.containsAll(rule.categoryIds())
                    || !categories.containsAll(rule.excludedCategoryIds()))
                throw bad("商品分类已变化，请重新选择：" + rule.name());
            if (!rule.excluded()
                    && "QUANTITY".equals(rule.method())
                    && !units.contains(rule.unitCode())) throw bad("请选择有效的计价单位：" + rule.name());
        }
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
