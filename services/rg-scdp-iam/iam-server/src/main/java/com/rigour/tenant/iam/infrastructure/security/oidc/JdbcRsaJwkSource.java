package com.rigour.tenant.iam.infrastructure.security.oidc;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.text.ParseException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** 从IAM密钥目录动态读取发布公钥，并只为当前ACTIVE密钥装配私钥。 */
public final class JdbcRsaJwkSource implements JWKSource<SecurityContext> {

    private final JdbcTemplate jdbcTemplate;
    private final PrivateKeyReferenceResolver privateKeyResolver;
    private final int minimumRsaBits;

    public JdbcRsaJwkSource(
            JdbcTemplate jdbcTemplate,
            PrivateKeyReferenceResolver privateKeyResolver,
            int minimumRsaBits
    ) {
        if (minimumRsaBits < 3072) {
            throw new IllegalArgumentException("minimumRsaBits cannot be lower than 3072");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.privateKeyResolver = privateKeyResolver;
        this.minimumRsaBits = minimumRsaBits;
        loadValidatedKeys();
    }

    @Override
    public List<JWK> get(JWKSelector jwkSelector, SecurityContext context) throws KeySourceException {
        try {
            return jwkSelector.select(new JWKSet(loadValidatedKeys()));
        } catch (RuntimeException exception) {
            throw new KeySourceException("Cannot load validated IAM signing keys", exception);
        }
    }

    List<JWK> loadValidatedKeys() {
        LocalDateTime now = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        List<KeyRow> rows = jdbcTemplate.query("""
                        SELECT kid, public_jwk_json, private_key_ref, status
                          FROM iam_signing_key
                         WHERE status IN ('ACTIVE', 'VERIFY_ONLY')
                           AND not_before <= ? AND not_after > ?
                         ORDER BY CASE status WHEN 'ACTIVE' THEN 0 ELSE 1 END, activated_at DESC
                        """, (resultSet, rowNumber) -> new KeyRow(
                        resultSet.getString("kid"),
                        resultSet.getString("public_jwk_json"),
                        resultSet.getString("private_key_ref"),
                        resultSet.getString("status")), now, now);
        long activeCount = rows.stream().filter(row -> "ACTIVE".equals(row.status())).count();
        if (activeCount != 1) {
            throw new IllegalStateException("Exactly one currently valid ACTIVE signing key is required");
        }
        return rows.stream().map(this::toJwk).map(JWK.class::cast).toList();
    }

    private RSAKey toJwk(KeyRow row) {
        RSAKey publicJwk;
        try {
            publicJwk = RSAKey.parse(row.publicJwkJson());
        } catch (ParseException exception) {
            throw new IllegalStateException("Signing public JWK cannot be parsed", exception);
        }
        if (publicJwk.isPrivate()
                || !row.kid().equals(publicJwk.getKeyID())
                || !KeyUse.SIGNATURE.equals(publicJwk.getKeyUse())
                || !JWSAlgorithm.RS256.equals(publicJwk.getAlgorithm())
                || publicJwk.size() < minimumRsaBits) {
            throw new IllegalStateException("Signing public JWK violates RS256 catalog constraints");
        }
        if (!"ACTIVE".equals(row.status())) {
            return publicJwk;
        }

        PrivateKey privateKey = privateKeyResolver.resolve(row.privateKeyReference());
        if (!(privateKey instanceof RSAPrivateKey rsaPrivateKey)
                || !rsaPrivateKey.getModulus().equals(publicJwk.getModulus().decodeToBigInteger())) {
            throw new IllegalStateException("Signing private key does not match catalog public JWK");
        }
        return new RSAKey.Builder(publicJwk).privateKey(rsaPrivateKey).build();
    }

    private record KeyRow(
            String kid,
            String publicJwkJson,
            String privateKeyReference,
            String status
    ) {
    }
}
