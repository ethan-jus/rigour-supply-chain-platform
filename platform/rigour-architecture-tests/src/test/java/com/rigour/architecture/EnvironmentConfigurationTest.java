package com.rigour.architecture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 用真实Spring配置加载器验证三种环境，不创建应用、不连接共享数据库。 */
class EnvironmentConfigurationTest {
    private static final Path ROOT = Path.of(System.getProperty("repositoryRoot"));

    @TempDir
    Path temporary;

    @Test
    void everyApplicationUsesDesktopDevWithoutExternalFilesOrRegistration() throws Exception {
        List<Path> resources = applicationResources();
        assertEquals(11, resources.size());
        for (Path resource : resources) {
            var env = load(resource, "dev");
            assertEquals("192.168.12.7:18848", env.getProperty("spring.cloud.nacos.server-addr"));
            assertEquals("false", env.getProperty("spring.cloud.nacos.discovery.register-enabled"));
            assertEquals("false", env.getProperty("spring.cloud.nacos.config.enabled"));
            boolean migrations = Files.isDirectory(resource.resolve("db/migration"));
            assertEquals(migrations ? "true" : "false", env.getProperty("spring.flyway.enabled"));
            if (migrations) {
                assertNull(env.getProperty("spring.flyway.url"), "Flyway直接复用当前数据源");
                assertNull(env.getProperty("spring.flyway.user"));
                assertNull(env.getProperty("spring.flyway.password"));
                assertEquals("true", env.getProperty("spring.flyway.validate-on-migrate"));
                assertEquals("true", env.getProperty("spring.flyway.clean-disabled"));
                assertEquals("false", env.getProperty("spring.flyway.baseline-on-migrate"));
                assertEquals("false", env.getProperty("spring.flyway.out-of-order"));
            }
            assertFalse(Files.readString(resource.resolve("application-dev.yml")).contains("spring.config.import"));
            assertNull(env.getProperty("spring.config.import"), "DEV不依赖额外配置文件");
            assertNotNull(env.getProperty("spring.application.name"));
            if (!resource.toString().contains("gateway-server")) {
                assertTrue(env.getProperty("spring.datasource.url").startsWith("jdbc:mysql://192.168.12.7:13306/rigour_"));
                assertEquals("root", env.getProperty("spring.datasource.username"));
                assertFalse(env.getProperty("spring.datasource.password").isBlank());
                if (!migrations) assertNull(env.getProperty("spring.flyway.password"));
                assertEquals("192.168.12.7", env.getProperty("spring.data.redis.host"));
                assertEquals("16379", env.getProperty("spring.data.redis.port"));
            }
        }
    }

    @Test
    void localInheritsDevAndPersonalOverridesTakePrecedence() throws Exception {
        for (Path source : applicationResources()) {
            Path resource = Files.createDirectory(temporary.resolve(source.getParent().getParent().getParent().getFileName()));
            for (String file : List.of("application.yml", "application-dev.yml", "application-local.yml")) {
                Files.copy(source.resolve(file), resource.resolve(file));
            }
            Path local = resource.resolve("application-local.yml");
            Files.writeString(local, Files.readString(local).replaceFirst("port: \\d+", "port: 28000")
                    + "spring:\n  datasource:\n    url: jdbc:mysql://localhost:3306/personal_test\n");
            var env = load(resource, "local");
            assertEquals("28000", env.getProperty("server.port"));
            assertEquals("jdbc:mysql://localhost:3306/personal_test", env.getProperty("spring.datasource.url"));
            assertEquals("192.168.12.7:18848", env.getProperty("spring.cloud.nacos.server-addr"));
            assertEquals("false", env.getProperty("spring.cloud.nacos.discovery.register-enabled"));
            if (Files.isDirectory(source.resolve("db/migration"))) {
                assertEquals("true", env.getProperty("spring.flyway.enabled"));
                assertNull(env.getProperty("spring.flyway.url"),
                        "迁移必须复用个人覆盖后的数据源，不能保留独立的共享DEV地址");
            }
        }
    }

    @Test
    void productionDoesNotFallBackToDevOrInsecureAuthentication() throws Exception {
        for (Path resource : applicationResources()) {
            String text = Files.readString(resource.resolve("application-prod.yml"));
            assertFalse(text.contains("192.168.12.7"));
            assertFalse(text.contains("82.157.4.176"));
            var env = load(resource, "prod");
            assertThrows(IllegalArgumentException.class, () -> env.getProperty("spring.cloud.nacos.server-addr"));
            if (!resource.toString().contains("gateway-server")) {
                assertThrows(IllegalArgumentException.class, () -> env.getProperty("spring.datasource.url"));
            }
            if (resource.toString().contains("rg-scdp-iam")) {
                assertEquals("true", env.getProperty("server.servlet.session.cookie.secure"));
                assertEquals("false", env.getProperty("rigour.iam.oidc.server.allow-insecure-lan"));
            }
        }
    }

    private static List<Path> applicationResources() throws Exception {
        try (var paths = Files.walk(ROOT.resolve("services"))) {
            return paths.filter(path -> path.endsWith("src/main/resources/application.yml"))
                    .map(Path::getParent).sorted().toList();
        }
    }

    private static StandardEnvironment load(Path resource, String profile) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "spring.config.location", resource.resolve("application.yml").toUri().toString(),
                "spring.profiles.active", profile)));
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        return environment;
    }
}
