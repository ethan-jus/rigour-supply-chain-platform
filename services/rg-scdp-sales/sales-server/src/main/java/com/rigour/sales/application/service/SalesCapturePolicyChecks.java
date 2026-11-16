package com.rigour.sales.application.service;
import com.rigour.sales.application.port.out.SalesWorkQueryRepository.VisitPolicy;
import com.rigour.shared.core.exception.RequestValidationException;
import java.math.BigDecimal;
final class SalesCapturePolicyChecks {
    private SalesCapturePolicyChecks() {}
    static void location(VisitPolicy policy,BigDecimal accuracy) {
        var rules=policy.captureRules();
        Integer maximum=rules==null ? null : rules.maximumLocationAccuracyMeters();
        if(maximum!=null && (accuracy==null || accuracy.signum()<=0 || accuracy.doubleValue()>maximum))
            throw new com.rigour.shared.core.exception.BusinessException(com.rigour.shared.core.api.ErrorCode.LOCATION_ACCURACY_INSUFFICIENT,"当前定位精度不满足政策，请重新定位（要求不超过"+maximum+"米）",java.util.List.of());
    }
    static void photo(VisitPolicy policy,String source) {
        var rules=policy.captureRules();
        if("APP_CAMERA".equals(source) || rules==null && "FEISHU_CAMERA".equals(source)) return;
        // Import flags remain in historical policy snapshots, but cannot enable new external evidence.
        throw new com.rigour.shared.core.exception.BusinessException(com.rigour.shared.core.api.ErrorCode.SALES_EVIDENCE_INVALID,"当前拜访政策只允许App现场拍照",java.util.List.of());
    }
    static void recording(VisitPolicy policy,String source) {
        var rules=policy.captureRules();
        if(rules==null && source==null || "APP_RECORDER".equals(source)) return;
        throw new RequestValidationException("当前拜访政策只允许App现场录音");
    }
}
