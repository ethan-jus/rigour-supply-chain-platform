package com.rigour.hr.application.service.support;

import com.rigour.hr.api.v1.model.HrEmployeeCommand;
import com.rigour.hr.domain.organization.EmployeeIdentityNumber;
import com.rigour.shared.core.api.ApiErrorDetail;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;

/** Manual employee maintenance validates the whole archive before any write. */
public final class HrEmployeeValidation {
    private HrEmployeeValidation() {}

    public static void requireValid(HrEmployeeCommand c) {
        var errors = new LinkedHashMap<String, String>();
        required(errors, "employeeName", c.employeeName(), "姓名", 128);
        if (c.departmentId() == null || c.departmentId() <= 0)
            errors.put("departmentId", "请选择有效部门");
        required(errors, "positionCode", c.positionCode(), "岗位", 50);
        required(errors, "mobile", c.mobile(), "手机号", 32);
        if (present(c.mobile()) && !value(c.mobile()).matches("1[3-9][0-9]{9}"))
            errors.put("mobile", "请输入有效的11位手机号");
        if (c.entryDate() == null) errors.put("entryDate", "请填写入职日期");
        var entry =
                c.entryDate() == null
                        ? null
                        : c.entryDate().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        if (c.employmentStatus() == null
                || !List.of("ACTIVE", "LEFT").contains(c.employmentStatus()))
            errors.put("employmentStatus", "请选择在职或离职");
        if (!present(c.payrollCategory())) errors.put("payrollCategory", "请选择薪酬类别");
        else if (!List.of("SALES", "HEADQUARTERS").contains(c.payrollCategory()))
            errors.put("payrollCategory", "薪酬类别无效");
        if (c.regularized() == null) errors.put("regularized", "请选择是否转正");
        if (Boolean.TRUE.equals(c.regularized()) && c.regularizationDate() == null)
            errors.put("regularizationDate", "请填写实际转正日期");
        if (c.regularizationDate() != null) {
            if (!Boolean.TRUE.equals(c.regularized()))
                errors.put("regularizationDate", "仅已转正员工可填写转正日期");
            else if (entry != null && c.regularizationDate().isBefore(entry))
                errors.put("regularizationDate", "转正日期不能早于入职日期");
            else if (c.regularizationDate().isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))
                errors.put("regularizationDate", "实际转正日期不能晚于今天");
        }
        if ("LEFT".equals(c.employmentStatus()) && c.leaveDate() == null)
            errors.put("leaveDate", "离职员工必须填写离职日期");
        if (!"LEFT".equals(c.employmentStatus()) && c.leaveDate() != null)
            errors.put("leaveDate", "仅离职员工可填写离职日期");
        if (c.entryDate() != null && c.leaveDate() != null && c.leaveDate().isBefore(c.entryDate()))
            errors.put("leaveDate", "离职日期不能早于入职日期");
        optional(errors, "email", c.email(), "邮箱", 128);
        if (present(c.email()) && !value(c.email()).matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
            errors.put("email", "邮箱格式不正确");
        optional(errors, "jobGrade", c.jobGrade(), "职级", 32);
        optional(errors, "remark", c.remark(), "备注", 500);
        var p = c.profile();
        required(errors, "profile.idNumber", p == null ? null : p.idNumber(), "身份证号", 18);
        if (p != null && present(p.idNumber())) {
            try {
                var id = EmployeeIdentityNumber.normalize(p.idNumber());
                // A valid identity number supplies gender and birth date; do not accept fabricated
                // fields.
                var birth =
                        LocalDate.of(
                                Integer.parseInt(id.substring(6, 10)),
                                Integer.parseInt(id.substring(10, 12)),
                                Integer.parseInt(id.substring(12, 14)));
                if (entry != null && entry.isBefore(birth)) errors.put("entryDate", "入职日期不能早于出生日期");
            } catch (IllegalArgumentException ex) {
                errors.put("profile.idNumber", ex.getMessage());
            }
        }
        if (p != null) {
            optional(errors, "profile.education", p.education(), "学历", 32);
            optional(errors, "profile.graduationSchool", p.graduationSchool(), "毕业院校", 128);
            optional(errors, "profile.major", p.major(), "专业", 128);
            optional(errors, "profile.registeredAddress", p.registeredAddress(), "户籍地址", 300);
            optional(errors, "profile.residentialAddress", p.residentialAddress(), "现居住地", 300);
            optional(errors, "profile.householdType", p.householdType(), "户口性质", 32);
            optional(errors, "profile.bankAccount", p.bankAccount(), "银行卡号", 32);
            optional(errors, "profile.bankName", p.bankName(), "开户行", 128);
            optional(errors, "profile.socialInsurance", p.socialInsurance(), "参保状态", 32);
            optional(errors, "profile.emergencyContact", p.emergencyContact(), "紧急联系人", 128);
            optional(errors, "profile.emergencyPhone", p.emergencyPhone(), "紧急联系方式", 32);
            optional(errors, "profile.probationPeriod", p.probationPeriod(), "试用期", 32);
            try {
                normalizeProbationPeriod(p.probationPeriod());
            } catch (IllegalArgumentException ex) {
                errors.put("profile.probationPeriod", ex.getMessage());
            }
            optional(errors, "profile.regularSalary", p.regularSalary(), "转正底薪", 64);
            optional(errors, "profile.probationSalary", p.probationSalary(), "试用期底薪", 64);
            if (present(p.bankAccount()) && !value(p.bankAccount()).matches("[0-9]{10,32}"))
                errors.put("profile.bankAccount", "银行卡号须为10至32位数字");
            if (present(p.emergencyPhone())
                    && !value(p.emergencyPhone())
                            .matches(
                                    "(?:1[3-9][0-9]{9}|0[0-9]{2,3}-?[0-9]{7,8}(?:-[0-9]{1,6})?|\\+[1-9][0-9]{5,19})"))
                errors.put("profile.emergencyPhone", "紧急联系方式须为有效手机号、座机号或带区号的国际号码");
            if (p.contractEndDate() != null && entry != null && p.contractEndDate().isBefore(entry))
                errors.put("profile.contractEndDate", "合同截止日期不能早于入职日期");
            if (present(p.socialInsurance())
                    && !List.of("五险一金", "五险", "公积金", "未参保").contains(value(p.socialInsurance())))
                errors.put("profile.socialInsurance", "参保状态选项无效");
            if (present(p.education())
                    && !List.of("小学", "初中", "高中", "中专", "大专", "本科", "硕士", "博士", "其他")
                            .contains(value(p.education())))
                errors.put("profile.education", "学历选项无效");
            if (present(p.householdType())
                    && !List.of("农业户口", "非农业户口", "居民户口", "其他").contains(value(p.householdType())))
                errors.put("profile.householdType", "户口性质选项无效");
            if ("SALES".equals(c.payrollCategory())) {
                for (var field : List.of("regularSalary", "probationSalary")) {
                    var salary =
                            "regularSalary".equals(field) ? p.regularSalary() : p.probationSalary();
                    if (present(salary) && !value(salary).matches("[0-9]{1,14}(\\.[0-9]{1,2})?"))
                        errors.put("profile." + field, "销售薪资档案仅填写底薪金额，最多两位小数；绩效、提成和补贴在薪酬配置维护");
                }
            }
        }
        if (!errors.isEmpty())
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    String.join("；", errors.values()),
                    errors.entrySet().stream()
                            .map(e -> new ApiErrorDetail(e.getKey(), "INVALID_VALUE", e.getValue()))
                            .toList());
    }

    public static String normalizeProbationPeriod(String raw) {
        if (!present(raw)) return null;
        var matcher = java.util.regex.Pattern.compile("([0-9]+)\\s*(?:个?月)?").matcher(value(raw));
        if (!matcher.matches()) throw new IllegalArgumentException("试用期请填写不小于0的整数，单位为月");
        var months = new java.math.BigInteger(matcher.group(1));
        if (months.compareTo(new java.math.BigInteger("9007199254740991")) > 0)
            throw new IllegalArgumentException("试用期请填写不小于0的整数，单位为月");
        return months.toString();
    }

    private static String value(String value) {
        return value == null ? "" : value.strip();
    }

    private static boolean present(String value) {
        return !value(value).isEmpty();
    }

    private static void required(
            LinkedHashMap<String, String> errors, String key, String value, String label, int max) {
        if (!present(value)) errors.put(key, "请填写" + label);
        else optional(errors, key, value, label, max);
    }

    private static void optional(
            LinkedHashMap<String, String> errors, String key, String value, String label, int max) {
        if (value(value).length() > max) errors.put(key, label + "长度不能超过" + max + "字");
    }
}
