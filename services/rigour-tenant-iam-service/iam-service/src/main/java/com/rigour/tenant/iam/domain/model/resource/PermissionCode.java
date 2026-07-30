package com.rigour.tenant.iam.domain.model.resource;

import java.util.Objects;
import java.util.regex.Pattern;

/** 形如`iam:user:read`的稳定权限标识值对象。 */
public record PermissionCode(String value) {

    private static final Pattern FORMAT = Pattern.compile(
            "[a-z][a-z0-9-]*:[a-z][a-z0-9-]*:[a-z][a-z0-9-]*"
    );

    public PermissionCode {
        Objects.requireNonNull(value, "permission code must not be null");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid permission code: " + value);
        }
    }
}
