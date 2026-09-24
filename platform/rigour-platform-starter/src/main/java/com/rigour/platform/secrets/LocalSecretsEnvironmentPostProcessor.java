package com.rigour.platform.secrets;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** 连接 Nacos 前加载；默认关闭，DEV 和未迁移的服务保持原配置方式。 */
public final class LocalSecretsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    static final String SOURCE_NAME = "rigourLocalSecrets";

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER - 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String enabled = environment.getProperty("RIGOUR_LOCAL_SECRETS_ENABLED", "false");
        if ("false".equalsIgnoreCase(enabled)) {
            return;
        }
        if (!"true".equalsIgnoreCase(enabled)) {
            throw new IllegalStateException("RIGOUR_LOCAL_SECRETS_ENABLED 必须为 true 或 false");
        }
        try {
            String directory = environment.getRequiredProperty("RIGOUR_LOCAL_SECRETS_DIR");
            String service = environment.getRequiredProperty("RIGOUR_LOCAL_SECRETS_SERVICE");
            Map<String, Object> values = new LinkedHashMap<>(new LocalSecretStore(Path.of(directory)).load(service));
            // 同时满足既有本地 YAML 的占位符，主配置使用规范属性名以覆盖 Nacos 旧值。
            values.put("NACOS_USERNAME", values.get("spring.cloud.nacos.username"));
            values.put("NACOS_PASSWORD", values.get("spring.cloud.nacos.password"));
            values.put("RIGOUR_CONTEXT_TRUST_KEY_V1", values.get("rigour.context.trust.keys-base64.v1"));
            values.put("management.endpoint.env.show-values", "never");
            values.put("management.endpoint.configprops.show-values", "never");
            values.put("management.endpoint.heapdump.access", "none");
            environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, values));
        } catch (Exception e) {
            // 不携带底层异常，防止原始输入进入启动失败分析或日志。
            throw new IllegalStateException("本机凭据加载失败：检查主密钥、密文、权限和必填项；拒绝回退启动");
        }
    }
}
