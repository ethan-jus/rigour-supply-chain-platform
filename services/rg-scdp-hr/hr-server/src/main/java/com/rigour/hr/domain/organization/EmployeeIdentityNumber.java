package com.rigour.hr.domain.organization;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Locale;

/** 校验居民身份证的日期和校验位；不依赖外部身份查询。 */
public final class EmployeeIdentityNumber {
    private EmployeeIdentityNumber() {}
    public static String normalize(String raw) {
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!value.matches("[1-9][0-9]{16}[0-9X]")) throw new IllegalArgumentException("请输入有效的18位身份证号");
        try {
            LocalDate birth = LocalDate.parse(value.substring(6,14), DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT));
            if (birth.isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")))) throw new IllegalArgumentException("身份证出生日期无效");
        } catch (java.time.format.DateTimeParseException ex) { throw new IllegalArgumentException("身份证出生日期无效"); }
        int[] weights = {7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2};
        int sum = 0;
        for (int i=0;i<17;i++) sum += (value.charAt(i)-'0')*weights[i];
        if ("10X98765432".charAt(sum%11) != value.charAt(17)) throw new IllegalArgumentException("身份证校验位无效");
        return value;
    }
}
