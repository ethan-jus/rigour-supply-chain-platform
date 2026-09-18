package com.rigour.integration.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Configuration;

/** 飞书来源在local YAML配置，继承DEV默认值时不依赖个人目录的外部文件。 */
class FeishuLocalYamlConfigurationTest {
    @TempDir Path directory;

    @Test
    void personalYamlSourcesCoexistWithInheritedDevConfiguration() throws Exception {
        Files.writeString(directory.resolve("application.properties"), "spring.application.name=fixture\n");
        Files.writeString(directory.resolve("application-dev.yml"),
                "capture-test:\n  dev-import-loaded: true\n");
        try (var stream = getClass().getResourceAsStream("/application-local.yml")) {
            assertThat(stream).isNotNull();
            Files.writeString(directory.resolve("application-local.yml"),
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                            + "rigour:\n  integration:\n    feishu:\n      reconciliation:\n"
                            + "        sources:\n          - id: local-fixture\n");
        }
        SpringApplication application = new SpringApplication(ProbeConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);
        try (var context = application.run("--spring.config.location=" + directory.toUri(),
                "--spring.profiles.active=local", "--spring.cloud.nacos.config.enabled=false",
                "--spring.cloud.nacos.discovery.enabled=false")) {
            assertThat(context.getEnvironment().getProperty("capture-test.dev-import-loaded")).isEqualTo("true");
            assertThat(context.getEnvironment().getProperty("rigour.integration.feishu.reconciliation.sources[0].id"))
                    .isEqualTo("local-fixture");
        }
        try (var stream = getClass().getResourceAsStream("/application-dev.yml")) {
            assertThat(stream).isNotNull();
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("192.168.12.7:18848")
                    .doesNotContain("import:", "feishu-reconciliation.properties");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ProbeConfiguration { }
}
