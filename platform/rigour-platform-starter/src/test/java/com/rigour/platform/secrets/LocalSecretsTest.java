package com.rigour.platform.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Map;
import javax.crypto.AEADBadTagException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class LocalSecretsTest {
    @TempDir
    Path temporary;

    @Test
    void roundTripPreservesSpecialCharactersWithoutWritingPlaintext() throws Exception {
        LocalSecretStore store = common("dummy-password");
        String password = " space 中文 ' \" \\ $ # : = end ";
        store.set("iam", "spring.datasource.username", "dummy-user");
        store.set("iam", "spring.datasource.password", password);
        byte[] first = Files.readAllBytes(encrypted("iam"));
        assertThat(store.load("iam").get("spring.datasource.password")).isEqualTo(password);
        assertThat(new String(first, StandardCharsets.ISO_8859_1)).doesNotContain("spring.datasource", "dummy-user");
        store.set("iam", "spring.datasource.password", password);
        assertThat(Files.readAllBytes(encrypted("iam"))).isNotEqualTo(first);
        assertThat(Files.getPosixFilePermissions(encrypted("iam")))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void tamperAndWrongKeyFailAuthentication() throws Exception {
        LocalSecretStore store = common("dummy-password");
        byte[] original = Files.readAllBytes(encrypted("common"));
        byte[] changed = original.clone();
        changed[changed.length - 1] ^= 1;
        Files.write(encrypted("common"), changed);
        assertThatThrownBy(() -> store.load("gateway")).isInstanceOf(AEADBadTagException.class);
        Files.write(encrypted("common"), original);
        Files.write(root().resolve("keys/master.key"), new byte[32]);
        assertThatThrownBy(() -> store.load("gateway")).isInstanceOf(AEADBadTagException.class);
    }

    @Test
    void ciphertextCannotBeMovedBetweenScopes() throws Exception {
        LocalSecretStore store = common("dummy-password");
        Files.copy(encrypted("common"), encrypted("iam"), StandardCopyOption.COPY_ATTRIBUTES);
        assertThatThrownBy(() -> store.load("iam")).isInstanceOf(AEADBadTagException.class);
    }

    @Test
    void initializeNeverReplacesAnExistingKey() throws Exception {
        LocalSecretStore store = new LocalSecretStore(root());
        store.initialize();
        byte[] key = Files.readAllBytes(root().resolve("keys/master.key"));
        assertThatThrownBy(store::initialize).isInstanceOf(java.io.IOException.class);
        assertThat(Files.readAllBytes(root().resolve("keys/master.key"))).isEqualTo(key);
        store.set("common", "spring.cloud.nacos.username", "dummy");
        Files.delete(root().resolve("keys/master.key"));
        assertThatThrownBy(store::initialize).hasMessageContaining("拒绝初始化");
        assertThat(root().resolve("keys/master.key")).doesNotExist();
    }

    @Test
    void insecurePermissionsAndSymlinkAreRejected() throws Exception {
        LocalSecretStore store = common("dummy-password");
        Path path = encrypted("common");
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(() -> store.load("gateway")).isInstanceOf(java.io.IOException.class);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        Path moved = temporary.resolve("moved.enc");
        Files.move(path, moved);
        Files.createSymbolicLink(path, moved);
        assertThatThrownBy(() -> store.load("gateway")).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void missingFieldsInvalidTrustAndReferenceValuesAreRejected() throws Exception {
        LocalSecretStore store = common("dummy-password");
        store.set("iam", "spring.datasource.username", "dummy-user");
        assertThatThrownBy(() -> store.load("iam")).hasMessageContaining("spring.datasource.password");
        assertThatThrownBy(() -> store.set("iam", "spring.cloud.nacos.password", "dummy"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.set("../other", "spring.datasource.password", "dummy"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.set("common", "spring.cloud.nacos.password", "${OTHER}"))
                .isInstanceOf(IllegalArgumentException.class);
        store.set("common", "rigour.context.trust.keys-base64.v1", "invalid-base64");
        assertThatThrownBy(() -> store.load("gateway")).hasMessageContaining("Base64");
    }

    @Test
    void optOutLeavesEnvironmentUntouchedAndOptInFailsClosedWithoutSecretInException() {
        StandardEnvironment environment = new StandardEnvironment();
        LocalSecretsEnvironmentPostProcessor processor = new LocalSecretsEnvironmentPostProcessor();
        processor.postProcessEnvironment(environment, new SpringApplication());
        assertThat(environment.getPropertySources().contains(LocalSecretsEnvironmentPostProcessor.SOURCE_NAME)).isFalse();
        environment.getPropertySources().addFirst(new MapPropertySource("test-controls", controls()));
        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class).hasNoCause().hasMessageContaining("拒绝回退");
    }

    @Test
    void realBootStartupDiscoversProcessorBeforeConfigImportAndSecretsWinOverImportedConfig() throws Exception {
        Path imported = temporary.resolve("imported.properties");
        Files.writeString(imported, "test.import.loaded=yes\nspring.datasource.password=old-nacos-value\n");
        LocalSecretStore store = common(imported.toUri().toString());
        store.set("iam", "spring.datasource.username", "dummy-user");
        store.set("iam", "spring.datasource.password", "encrypted-database-value");
        Path bootstrap = temporary.resolve("application.properties");
        // 如果后处理器没有在 ConfigData 前运行，这个 import 会因为占位符缺失而失败。
        Files.writeString(bootstrap, "spring.config.import=${spring.cloud.nacos.password}\n");
        SpringApplication application = new SpringApplication(EmptyApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        Map<String, Object> defaults = new java.util.LinkedHashMap<>(controls());
        defaults.put("RIGOUR_LOCAL_SECRETS_SERVICE", "iam");
        defaults.put("spring.config.location", bootstrap.toUri().toString());
        defaults.put("spring.main.banner-mode", "off");
        application.setDefaultProperties(defaults);
        try (var context = application.run("--management.endpoint.env.show-values=always")) {
            var environment = context.getEnvironment();
            assertThat(environment.getProperty("test.import.loaded")).isEqualTo("yes");
            assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("encrypted-database-value");
            assertThat(environment.getProperty("NACOS_PASSWORD")).isEqualTo(imported.toUri().toString());
            assertThat(environment.getProperty("management.endpoint.env.show-values")).isEqualTo("never");
            assertThat(environment.getProperty("management.endpoint.heapdump.access")).isEqualTo("none");
        }
    }

    private Map<String, Object> controls() {
        return Map.of("RIGOUR_LOCAL_SECRETS_ENABLED", "true", "RIGOUR_LOCAL_SECRETS_DIR", root().toString(),
                "RIGOUR_LOCAL_SECRETS_SERVICE", "gateway");
    }

    private LocalSecretStore common(String password) throws Exception {
        LocalSecretStore store = new LocalSecretStore(root());
        store.initialize();
        store.set("common", "spring.cloud.nacos.username", "dummy-nacos-user");
        store.set("common", "spring.cloud.nacos.password", password);
        store.set("common", "rigour.context.trust.keys-base64.v1",
                Base64.getEncoder().encodeToString(new byte[32]));
        return store;
    }

    private Path root() {
        return temporary.resolve("local-secrets");
    }

    private Path encrypted(String scope) {
        return root().resolve("secrets/" + scope + ".enc");
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyApplication {
    }
}
