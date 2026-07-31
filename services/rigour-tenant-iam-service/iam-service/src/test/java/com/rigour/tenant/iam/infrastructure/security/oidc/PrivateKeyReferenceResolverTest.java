package com.rigour.tenant.iam.infrastructure.security.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrivateKeyReferenceResolverTest {

    @TempDir
    Path home;

    @Test
    void resolvesRestrictedPortableHomeFile() throws Exception {
        Path secret = home.resolve(".config/rigour/secrets/iam-dev-signing-v1.pem");
        Files.createDirectories(secret.getParent());
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        RSAPrivateKey expected = (RSAPrivateKey) generator.generateKeyPair().getPrivate();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(expected.getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        Files.writeString(secret, pem, StandardCharsets.US_ASCII);
        try {
            Files.setPosixFilePermissions(secret, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // 非POSIX测试文件系统仍覆盖路径和密钥匹配校验。
        }

        RSAPrivateKey actual = (RSAPrivateKey) new PrivateKeyReferenceResolver(home)
                .resolve("home-file:.config/rigour/secrets/iam-dev-signing-v1.pem");

        assertThat(actual.getModulus()).isEqualTo(expected.getModulus());
    }

    @Test
    void rejectsHomeFileTraversal() {
        assertThatThrownBy(() -> new PrivateKeyReferenceResolver(home).resolve("home-file:../private.pem"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("user.home");
    }
}
