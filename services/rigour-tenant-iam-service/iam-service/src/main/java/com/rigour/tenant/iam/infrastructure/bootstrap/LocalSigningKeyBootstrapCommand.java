package com.rigour.tenant.iam.infrastructure.bootstrap;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.rigour.tenant.iam.application.port.out.IdentifierGenerator;
import com.rigour.tenant.iam.infrastructure.persistence.UuidBinaryCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;

/** 本地开发首次启动生成受限RSA-3072文件；已有ACTIVE密钥时保持幂等。 */
public final class LocalSigningKeyBootstrapCommand implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final IdentifierGenerator ids;
    private final LocalSigningKeyBootstrapProperties properties;
    public LocalSigningKeyBootstrapCommand(JdbcTemplate jdbc, IdentifierGenerator ids,
                                           LocalSigningKeyBootstrapProperties properties) {
        this.jdbc = jdbc; this.ids = ids; this.properties = properties;
    }
    @Override
    public void run(ApplicationArguments args) throws Exception {
        ensure();
    }

    public void ensure() throws Exception {
        Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM iam_signing_key WHERE status='ACTIVE'", Integer.class);
        if (active != null && active == 1) return;
        if (active == null || active != 0) throw new IllegalStateException("Local signing bootstrap requires zero or one ACTIVE key");
        Path path = Path.of(properties.getPath()).toAbsolutePath().normalize();
        Files.createDirectories(path.getParent());
        if (Files.exists(path)) throw new IllegalStateException("Local signing key file exists without ACTIVE database metadata");
        String kid = "local-" + ids.nextId();
        RSAKey key = new RSAKeyGenerator(3072).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256)
                .keyID(kid).generate();
        String encoded = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(key.toPrivateKey().getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----\n";
        Files.writeString(path, pem, StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) { }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO iam_signing_key
                (id, kid, algorithm, key_use, public_jwk_json, private_key_ref, status,
                 not_before, not_after, activated_at, created_at)
                VALUES (?, ?, 'RS256', 'sig', CAST(? AS JSON), ?, 'ACTIVE', ?, ?, ?, ?)
                """, UuidBinaryCodec.encode(ids.nextId()), kid, key.toPublicJWK().toJSONString(),
                "file:" + path, now.minusMinutes(1), now.plusYears(1), now, now);
    }
}
