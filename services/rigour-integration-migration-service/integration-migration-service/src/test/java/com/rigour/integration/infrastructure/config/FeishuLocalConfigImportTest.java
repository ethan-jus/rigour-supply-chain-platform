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

class FeishuLocalConfigImportTest {
    @TempDir Path directory;

    @Test
    void localExternalImportDoesNotSuppressEarlierDevDocumentImport() throws Exception {
        Files.writeString(directory.resolve("application.properties"), "spring.application.name=fixture\n");
        Path shared = directory.resolve("shared-dev.properties");
        Files.writeString(shared, "capture-test.dev-import-loaded=true\n");
        Files.writeString(directory.resolve("application-dev.yml"),
                "spring:\n  config:\n    import: optional:" + shared.toUri() + "\n");
        try (var stream = getClass().getResourceAsStream("/application-local.yml")) {
            assertThat(stream).isNotNull();
            Files.write(directory.resolve("application-local.yml"), stream.readAllBytes());
        }
        Path home = directory.resolve("home");
        Path external = home.resolve(".config/rigour/feishu-reconciliation.properties");
        Files.createDirectories(external.getParent());
        Files.writeString(external, "rigour.integration.feishu.reconciliation.sources[0].id=local-fixture\n");
        SpringApplication application = new SpringApplication(ProbeConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);
        try (var context = application.run("--spring.config.location=" + directory.toUri(),
                "--spring.profiles.active=dev,local", "--spring.cloud.nacos.config.enabled=false",
                "--spring.cloud.nacos.discovery.enabled=false", "--user.home=" + home)) {
            assertThat(context.getEnvironment().getProperty("capture-test.dev-import-loaded")).isEqualTo("true");
            assertThat(context.getEnvironment().getProperty("rigour.integration.feishu.reconciliation.sources[0].id"))
                    .isEqualTo("local-fixture");
        }
        try (var stream = getClass().getResourceAsStream("/application-dev.yml")) {
            assertThat(stream).isNotNull();
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("import: optional:nacos:${spring.application.name}.yaml?refreshEnabled=true");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ProbeConfiguration { }
}
