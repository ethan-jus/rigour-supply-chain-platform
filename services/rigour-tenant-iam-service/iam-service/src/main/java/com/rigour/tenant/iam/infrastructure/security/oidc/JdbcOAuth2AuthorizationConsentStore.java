package com.rigour.tenant.iam.infrastructure.security.oidc;

import com.rigour.tenant.iam.infrastructure.persistence.UuidBinaryCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 将Spring Authorization Server的授权同意映射到可撤销的IAM同意记录。 */
public class JdbcOAuth2AuthorizationConsentStore implements OAuth2AuthorizationConsentService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcOAuth2AuthorizationConsentStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate cannot be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper cannot be null");
    }

    @Override
    @Transactional
    public void save(OAuth2AuthorizationConsent authorizationConsent) {
        Objects.requireNonNull(authorizationConsent, "authorizationConsent cannot be null");
        List<String> authorities = authorizationConsent.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).sorted().toList();
        if (authorities.isEmpty()) {
            throw new IllegalArgumentException("authorizationConsent.authorities cannot be empty");
        }
        jdbcTemplate.update("""
                        INSERT INTO iam_oauth_consent
                            (client_id, principal_name, authorities_json, consent_source,
                             granted_at, granted_by, revoked_at, version)
                        VALUES (?, ?, ?, 'USER', ?, NULL, NULL, 0)
                        ON DUPLICATE KEY UPDATE
                            authorities_json = VALUES(authorities_json),
                            consent_source = 'USER', granted_at = VALUES(granted_at),
                            granted_by = NULL, revoked_at = NULL, version = version + 1
                        """,
                clientId(authorizationConsent.getRegisteredClientId()),
                authorizationConsent.getPrincipalName(), writeJson(authorities), utcNow());
    }

    @Override
    @Transactional
    public void remove(OAuth2AuthorizationConsent authorizationConsent) {
        Objects.requireNonNull(authorizationConsent, "authorizationConsent cannot be null");
        jdbcTemplate.update("""
                        UPDATE iam_oauth_consent
                        SET revoked_at = ?, version = version + 1
                        WHERE client_id = ? AND principal_name = ? AND revoked_at IS NULL
                        """,
                utcNow(), clientId(authorizationConsent.getRegisteredClientId()),
                authorizationConsent.getPrincipalName());
    }

    @Override
    @Transactional(readOnly = true)
    public OAuth2AuthorizationConsent findById(String registeredClientId, String principalName) {
        if (registeredClientId == null || principalName == null || principalName.isBlank()) {
            return null;
        }
        List<String> jsonRows = jdbcTemplate.query("""
                        SELECT authorities_json
                        FROM iam_oauth_consent
                        WHERE client_id = ? AND principal_name = ? AND revoked_at IS NULL
                        """,
                (resultSet, rowNumber) -> resultSet.getString(1), clientId(registeredClientId), principalName);
        if (jsonRows.isEmpty()) {
            return null;
        }
        OAuth2AuthorizationConsent.Builder builder = OAuth2AuthorizationConsent.withId(
                registeredClientId, principalName);
        readJson(jsonRows.getFirst()).forEach(authority ->
                builder.authority(new SimpleGrantedAuthority(authority)));
        return builder.build();
    }

    private byte[] clientId(String value) {
        try {
            return UuidBinaryCodec.encode(UUID.fromString(value));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException("registeredClientId must be a UUID", exception);
        }
    }

    private String writeJson(List<String> authorities) {
        try {
            return objectMapper.writeValueAsString(authorities);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Cannot serialize OAuth consent authorities", exception);
        }
    }

    private List<String> readJson(String json) {
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Cannot deserialize OAuth consent authorities", exception);
        }
    }

    private static LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
    }
}
