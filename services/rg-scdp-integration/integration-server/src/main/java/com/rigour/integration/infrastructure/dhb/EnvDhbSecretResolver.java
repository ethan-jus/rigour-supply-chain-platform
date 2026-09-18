package com.rigour.integration.infrastructure.dhb;

import java.util.Objects;
import java.util.function.Function;

/**
 * 开发环境的 Secret 引用适配器。
 *
 * <p>仅支持 {@code env://PREFIX}，从进程环境的
 * {@code PREFIX_SERIAL_NUMBER} 和 {@code PREFIX_PASSWORD} 读取。生产环境应替换为
 * Vault/KMS 等 Secret Manager 实现；代码和 Nacos 只保存引用。</p>
 */
public final class EnvDhbSecretResolver implements DhbSecretResolver {

    private final Function<String, String> valueSource;

    public EnvDhbSecretResolver() {
        this(System::getenv);
    }

    EnvDhbSecretResolver(Function<String, String> valueSource) {
        this.valueSource = Objects.requireNonNull(valueSource, "valueSource cannot be null");
    }

    @Override
    public Credentials resolve(String secretRef) {
        String ref = secretRef == null ? "" : secretRef.strip();
        if (ref.isEmpty()) {
            throw new DhbClientException(
                    "DHB_SECRET_NOT_CONFIGURED", "订货宝 Secret 尚未配置引用", false, null, null);
        }
        if (!ref.startsWith("env://") || ref.length() <= "env://".length()) {
            throw new DhbClientException(
                    "DHB_SECRET_REF_UNSUPPORTED",
                    "订货宝连接未使用受支持的 Secret 引用（当前开发环境仅支持 env://PREFIX）",
                    false, null, null);
        }
        String prefix = ref.substring("env://".length()).strip();
        if (!prefix.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new DhbClientException(
                    "DHB_SECRET_REF_INVALID", "订货宝 Secret 引用格式无效", false, null, null);
        }
        String serialNumber = valueSource.apply(prefix + "_SERIAL_NUMBER");
        String password = valueSource.apply(prefix + "_PASSWORD");
        String adminCookie = valueSource.apply(prefix + "_ADMIN_COOKIE");
        if (serialNumber == null || serialNumber.isBlank() || password == null || password.isBlank()) {
            throw new DhbClientException(
                    "DHB_SECRET_NOT_CONFIGURED",
                    "订货宝 Secret 尚未配置接口账号和密码", false, null, null);
        }
        try {
            return new Credentials(serialNumber, password, adminCookie);
        } catch (IllegalArgumentException exception) {
            throw new DhbClientException(
                    "DHB_SECRET_NOT_CONFIGURED", "订货宝 Secret 尚未配置完整", false, null, null);
        }
    }

}
