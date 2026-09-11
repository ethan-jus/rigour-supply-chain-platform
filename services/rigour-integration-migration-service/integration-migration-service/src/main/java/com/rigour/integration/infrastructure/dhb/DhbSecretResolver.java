package com.rigour.integration.infrastructure.dhb;

/**
 * 订货宝 Secret 读取端口。
 *
 * <p>实现只能根据引用从 Secret 管理系统读取值。禁止把密码、API Key 或令牌放进
 * Connector、Nacos、请求 DTO 或日志。</p>
 */
public interface DhbSecretResolver {

    Credentials resolve(String secretRef);

    record Credentials(String serialNumber, String password, String adminCookie) {
        public Credentials {
            if (serialNumber == null || serialNumber.isBlank()
                    || password == null || password.isBlank()) {
                throw new IllegalArgumentException("订货宝 Secret 未提供接口账号或密码");
            }
            adminCookie = adminCookie == null || adminCookie.isBlank() ? null : adminCookie.strip();
        }

        public Credentials(String serialNumber, String password) {
            this(serialNumber, password, null);
        }

        @Override
        public String toString() {
            return "Credentials[serialNumber=[REDACTED], password=[REDACTED], adminCookie=[REDACTED]]";
        }
    }
}
