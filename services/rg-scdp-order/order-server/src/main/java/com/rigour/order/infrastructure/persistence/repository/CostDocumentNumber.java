package com.rigour.order.infrastructure.persistence.repository;

import com.rigour.shared.core.code.BusinessCodeGenerator;
import com.rigour.shared.core.code.BusinessCodeRule;

/** 为新建费用记录分配业务单号。来源标识用于匹配原记录。 */
final class CostDocumentNumber {
    private static final BusinessCodeGenerator GENERATOR = new BusinessCodeGenerator();
    private static final BusinessCodeRule RULE = BusinessCodeRule.millisecond("CST", 6);

    private CostDocumentNumber() {}

    static String next() {
        return GENERATOR.generate(RULE);
    }
}
