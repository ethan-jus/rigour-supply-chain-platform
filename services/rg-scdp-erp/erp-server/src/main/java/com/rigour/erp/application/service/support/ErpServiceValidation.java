package com.rigour.erp.application.service.support;

import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** ERP 应用服务通用入参校验；只放无业务状态的基础校验。 */
public final class ErpServiceValidation {
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private ErpServiceValidation() {
    }

    public static void checkRevision(Integer revision, boolean update) {
        if (update && (revision == null || revision < 1)) throw badRequest("revision必须大于0");
        if (!update && revision != null && revision != 0) throw badRequest("新增时revision必须为空或0");
    }

    public static void requireRevision(int revision) {
        if (revision < 1) throw badRequest("revision必须大于0");
    }

    public static Long requireId(Long id, String message) {
        if (id == null || id < 1) throw badRequest(message);
        return id;
    }

    public static Long optionalId(Long id, String name) {
        if (id == null) return null;
        if (id < 1) throw badRequest(name + "无效");
        return id;
    }

    public static int pageBegin(int value) {
        if (value < 0) throw badRequest("begin必须大于等于0");
        return value;
    }

    public static int pageStep(int value) {
        if (value < 1 || value > 200) throw badRequest("step必须在1到200之间");
        return value;
    }

    public static Integer ordinal(Integer value) {
        if (value == null) return 0;
        if (value < 0) throw badRequest("ordinal必须大于等于0");
        return value;
    }

    public static String defaultCode(String value, String name, String defaultValue) {
        String normalized = code(value, name, false);
        return normalized == null ? defaultValue : normalized;
    }

    public static String code(String value, String name, boolean required) {
        String normalized = upper(value);
        if (normalized == null) {
            if (required) throw badRequest(name + "不能为空");
            return null;
        }
        if (!CODE.matcher(normalized).matches()) throw badRequest(name + "格式无效");
        return normalized;
    }

    public static String required(String value, String message, int max) {
        String normalized = text(value, max, message.replace("不能为空", ""));
        if (normalized == null) throw badRequest(message);
        return normalized;
    }

    public static String text(String value, int max, String name) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > max) throw badRequest(name + "长度不能超过" + max);
        return normalized;
    }

    public static String value(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String upper(String value) {
        String normalized = text(value, 64, "code");
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
