package com.rigour.hr.application.service.support;

import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.util.List;

/** HR 服务参数校验工具；保持与 ERP 主数据服务相同的分页和文本语义。 */
public final class HrServiceValidation {
    private static final int DEFAULT_STEP = 20;
    private static final int MAX_STEP = 200;

    private HrServiceValidation() {
    }

    public static int pageBegin(int begin) {
        return Math.max(0, begin);
    }

    public static int pageStep(int step) {
        if (step <= 0) return DEFAULT_STEP;
        return Math.min(step, MAX_STEP);
    }

    public static Long requireId(Long id, String message) {
        if (id == null || id <= 0) throw badRequest(message);
        return id;
    }

    public static String required(String value, String message, int max) {
        String text = text(value, max, message);
        if (text == null) throw badRequest(message);
        return text;
    }

    public static String text(String value, int max, String fieldName) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > max) {
            throw badRequest(fieldName + "长度不能超过" + max);
        }
        return normalized;
    }

    public static String value(String value) {
        return value == null ? "-" : value;
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
