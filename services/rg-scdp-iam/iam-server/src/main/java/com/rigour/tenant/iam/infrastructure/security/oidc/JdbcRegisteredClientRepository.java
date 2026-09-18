package com.rigour.tenant.iam.infrastructure.security.oidc;

import com.rigour.tenant.iam.infrastructure.persistence.UuidBinaryCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 将Spring Authorization Server技术客户端映射到IAM规范化表。
 * 当前切片只支持自包含JWT、RS256、Refresh Token轮换，以及公开PKCE或Argon2id密钥客户端。
 */
public class JdbcRegisteredClientRepository implements RegisteredClientRepository {

    private static final Set<String> SUPPORTED_AUTH_METHODS = Set.of(
            ClientAuthenticationMethod.NONE.getValue(),
            ClientAuthenticationMethod.CLIENT_SECRET_BASIC.getValue()
    );
    private static final Set<String> SUPPORTED_GRANT_TYPES = Set.of(
            AuthorizationGrantType.AUTHORIZATION_CODE.getValue(),
            AuthorizationGrantType.REFRESH_TOKEN.getValue(),
            AuthorizationGrantType.CLIENT_CREDENTIALS.getValue()
    );

    private final JdbcTemplate jdbcTemplate;

    public JdbcRegisteredClientRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate cannot be null");
    }

    @Override
    @Transactional
    public void save(RegisteredClient registeredClient) {
        validate(registeredClient);
        UUID id = parseUuid(registeredClient.getId(), "registeredClient.id");
        byte[] idBytes = UuidBinaryCodec.encode(id);
        LocalDateTime now = utc(Instant.now());
        String clientType = isPublicClient(registeredClient) ? "PUBLIC" : "CONFIDENTIAL";

        int updated = jdbcTemplate.update("""
                        UPDATE iam_oauth_client
                        SET client_id = ?, client_id_issued_at = ?, client_secret_hash = ?,
                            client_secret_expires_at = ?, client_name = ?, client_type = ?,
                            require_pkce = ?, require_consent = ?, authorization_code_ttl_seconds = ?,
                            access_token_ttl_seconds = ?, refresh_token_ttl_seconds = ?,
                            reuse_refresh_tokens = ?, id_token_signature_algorithm = ?, status = 'ACTIVE',
                            version = version + 1, updated_at = ?
                        WHERE id = ?
                        """,
                registeredClient.getClientId(), utc(registeredClient.getClientIdIssuedAt()),
                registeredClient.getClientSecret(), utc(registeredClient.getClientSecretExpiresAt()),
                registeredClient.getClientName(), clientType,
                registeredClient.getClientSettings().isRequireProofKey(),
                registeredClient.getClientSettings().isRequireAuthorizationConsent(),
                seconds(registeredClient.getTokenSettings().getAuthorizationCodeTimeToLive()),
                seconds(registeredClient.getTokenSettings().getAccessTokenTimeToLive()),
                seconds(registeredClient.getTokenSettings().getRefreshTokenTimeToLive()),
                registeredClient.getTokenSettings().isReuseRefreshTokens(),
                registeredClient.getTokenSettings().getIdTokenSignatureAlgorithm().getName(), now, idBytes);

        if (updated == 0) {
            jdbcTemplate.update("""
                            INSERT INTO iam_oauth_client (
                                id, application_id, client_id, client_id_issued_at, client_secret_hash,
                                client_secret_expires_at, client_name, client_type, require_pkce,
                                require_consent, authorization_code_ttl_seconds, access_token_ttl_seconds,
                                refresh_token_ttl_seconds, reuse_refresh_tokens, id_token_signature_algorithm,
                                status, version, created_at, created_by, updated_at, updated_by
                            ) VALUES (?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 0, ?, NULL, ?, NULL)
                            """,
                    idBytes, registeredClient.getClientId(), utc(registeredClient.getClientIdIssuedAt()),
                    registeredClient.getClientSecret(), utc(registeredClient.getClientSecretExpiresAt()),
                    registeredClient.getClientName(), clientType,
                    registeredClient.getClientSettings().isRequireProofKey(),
                    registeredClient.getClientSettings().isRequireAuthorizationConsent(),
                    seconds(registeredClient.getTokenSettings().getAuthorizationCodeTimeToLive()),
                    seconds(registeredClient.getTokenSettings().getAccessTokenTimeToLive()),
                    seconds(registeredClient.getTokenSettings().getRefreshTokenTimeToLive()),
                    registeredClient.getTokenSettings().isReuseRefreshTokens(),
                    registeredClient.getTokenSettings().getIdTokenSignatureAlgorithm().getName(), now, now);
        }

        replaceChildren(registeredClient, idBytes, now);
    }

    @Override
    @Transactional(readOnly = true)
    public RegisteredClient findById(String id) {
        return findOne("id = ?", UuidBinaryCodec.encode(parseUuid(id, "registeredClient.id")));
    }

    @Override
    @Transactional(readOnly = true)
    public RegisteredClient findByClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return null;
        }
        return findOne("client_id = ?", clientId);
    }

    private RegisteredClient findOne(String predicate, Object value) {
        List<ClientRow> rows = jdbcTemplate.query("""
                        SELECT id, client_id, client_id_issued_at, client_secret_hash,
                               client_secret_expires_at, client_name, require_pkce, require_consent,
                               authorization_code_ttl_seconds, access_token_ttl_seconds,
                               refresh_token_ttl_seconds, reuse_refresh_tokens, id_token_signature_algorithm
                        FROM iam_oauth_client
                        WHERE %s AND status = 'ACTIVE'
                        """.formatted(predicate), this::mapClientRow, value);
        if (rows.isEmpty()) {
            return null;
        }
        ClientRow row = rows.getFirst();
        byte[] idBytes = UuidBinaryCodec.encode(row.id());
        RegisteredClient.Builder builder = RegisteredClient.withId(row.id().toString())
                .clientId(row.clientId())
                .clientIdIssuedAt(row.clientIdIssuedAt())
                .clientName(row.clientName())
                .clientAuthenticationMethods(methods -> methods.addAll(loadAuthMethods(idBytes)))
                .authorizationGrantTypes(grants -> grants.addAll(loadGrantTypes(idBytes)))
                .redirectUris(uris -> uris.addAll(loadUris(idBytes, "LOGIN_REDIRECT")))
                .postLogoutRedirectUris(uris -> uris.addAll(loadUris(idBytes, "POST_LOGOUT_REDIRECT")))
                .scopes(scopes -> scopes.addAll(loadScopes(idBytes)))
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(row.requirePkce())
                        .requireAuthorizationConsent(row.requireConsent())
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .authorizationCodeTimeToLive(Duration.ofSeconds(row.authorizationCodeTtlSeconds()))
                        .accessTokenTimeToLive(Duration.ofSeconds(row.accessTokenTtlSeconds()))
                        .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                        .refreshTokenTimeToLive(Duration.ofSeconds(row.refreshTokenTtlSeconds()))
                        .reuseRefreshTokens(row.reuseRefreshTokens())
                        .idTokenSignatureAlgorithm(SignatureAlgorithm.from(row.idTokenSignatureAlgorithm()))
                        .build());
        if (row.clientSecretHash() != null) {
            builder.clientSecret(row.clientSecretHash());
        }
        if (row.clientSecretExpiresAt() != null) {
            builder.clientSecretExpiresAt(row.clientSecretExpiresAt());
        }
        return builder.build();
    }

    private void replaceChildren(RegisteredClient client, byte[] id, LocalDateTime now) {
        jdbcTemplate.update("DELETE FROM iam_oauth_client_auth_method WHERE client_id = ?", id);
        jdbcTemplate.update("DELETE FROM iam_oauth_client_grant WHERE client_id = ?", id);
        jdbcTemplate.update("DELETE FROM iam_oauth_client_redirect_uri WHERE client_id = ?", id);
        jdbcTemplate.update("DELETE FROM iam_oauth_client_scope WHERE client_id = ?", id);

        for (ClientAuthenticationMethod method : client.getClientAuthenticationMethods()) {
            jdbcTemplate.update("INSERT INTO iam_oauth_client_auth_method (client_id, auth_method, created_at) VALUES (?, ?, ?)",
                    id, method.getValue(), now);
        }
        for (AuthorizationGrantType grantType : client.getAuthorizationGrantTypes()) {
            jdbcTemplate.update("INSERT INTO iam_oauth_client_grant (client_id, grant_type, created_at) VALUES (?, ?, ?)",
                    id, grantType.getValue(), now);
        }
        for (String uri : client.getRedirectUris()) {
            insertUri(id, "LOGIN_REDIRECT", uri, now);
        }
        for (String uri : client.getPostLogoutRedirectUris()) {
            insertUri(id, "POST_LOGOUT_REDIRECT", uri, now);
        }
        for (String scope : client.getScopes()) {
            jdbcTemplate.update("INSERT INTO iam_oauth_client_scope (client_id, scope_code, created_at) VALUES (?, ?, ?)",
                    id, scope, now);
        }
    }

    private void insertUri(byte[] clientId, String type, String uri, LocalDateTime now) {
        jdbcTemplate.update("""
                        INSERT INTO iam_oauth_client_redirect_uri
                            (id, client_id, uri_type, uri, uri_hash, status, created_at, created_by)
                        VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, NULL)
                        """,
                UuidBinaryCodec.encode(UUID.randomUUID()), clientId, type, uri, sha256(uri), now);
    }

    private Set<ClientAuthenticationMethod> loadAuthMethods(byte[] id) {
        return Set.copyOf(jdbcTemplate.query(
                "SELECT auth_method FROM iam_oauth_client_auth_method WHERE client_id = ? ORDER BY auth_method",
                (resultSet, rowNumber) -> new ClientAuthenticationMethod(resultSet.getString(1)), id));
    }

    private Set<AuthorizationGrantType> loadGrantTypes(byte[] id) {
        return Set.copyOf(jdbcTemplate.query(
                "SELECT grant_type FROM iam_oauth_client_grant WHERE client_id = ? ORDER BY grant_type",
                (resultSet, rowNumber) -> new AuthorizationGrantType(resultSet.getString(1)), id));
    }

    private Set<String> loadUris(byte[] id, String type) {
        return Set.copyOf(jdbcTemplate.query(
                "SELECT uri FROM iam_oauth_client_redirect_uri "
                        + "WHERE client_id = ? AND uri_type = ? AND status = 'ACTIVE' ORDER BY uri",
                (resultSet, rowNumber) -> resultSet.getString(1), id, type));
    }

    private Set<String> loadScopes(byte[] id) {
        return Set.copyOf(jdbcTemplate.query(
                "SELECT scope_code FROM iam_oauth_client_scope WHERE client_id = ? ORDER BY scope_code",
                (resultSet, rowNumber) -> resultSet.getString(1), id));
    }

    private ClientRow mapClientRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ClientRow(
                UuidBinaryCodec.decode(resultSet.getBytes("id")),
                resultSet.getString("client_id"),
                instant(resultSet.getObject("client_id_issued_at", LocalDateTime.class)),
                resultSet.getString("client_secret_hash"),
                instant(resultSet.getObject("client_secret_expires_at", LocalDateTime.class)),
                resultSet.getString("client_name"),
                resultSet.getBoolean("require_pkce"),
                resultSet.getBoolean("require_consent"),
                resultSet.getLong("authorization_code_ttl_seconds"),
                resultSet.getLong("access_token_ttl_seconds"),
                resultSet.getLong("refresh_token_ttl_seconds"),
                resultSet.getBoolean("reuse_refresh_tokens"),
                resultSet.getString("id_token_signature_algorithm")
        );
    }

    private void validate(RegisteredClient client) {
        Objects.requireNonNull(client, "registeredClient cannot be null");
        if (client.getClientAuthenticationMethods().isEmpty()) {
            throw new IllegalArgumentException("clientAuthenticationMethods cannot be empty");
        }
        Set<String> authMethods = client.getClientAuthenticationMethods().stream()
                .map(ClientAuthenticationMethod::getValue).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!SUPPORTED_AUTH_METHODS.containsAll(authMethods)) {
            throw new IllegalArgumentException("Unsupported client authentication method: " + authMethods);
        }
        if (authMethods.contains(ClientAuthenticationMethod.NONE.getValue()) && authMethods.size() != 1) {
            throw new IllegalArgumentException("Public client authentication method 'none' cannot be combined");
        }
        if (isPublicClient(client)) {
            if (client.getClientSecret() != null || client.getClientSecretExpiresAt() != null
                    || !client.getClientSettings().isRequireProofKey()) {
                throw new IllegalArgumentException("Public clients require PKCE and cannot persist a secret");
            }
        } else if (authMethods.contains(ClientAuthenticationMethod.CLIENT_SECRET_BASIC.getValue())
                && (client.getClientSecret() == null || !client.getClientSecret().startsWith("$argon2id$"))) {
            throw new IllegalArgumentException("client_secret_basic requires a pre-encoded Argon2id secret");
        }
        Set<String> grantTypes = client.getAuthorizationGrantTypes().stream()
                .map(AuthorizationGrantType::getValue).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!SUPPORTED_GRANT_TYPES.containsAll(grantTypes)) {
            throw new IllegalArgumentException("Unsupported authorization grant type: " + grantTypes);
        }
        if (!OAuth2TokenFormat.SELF_CONTAINED.equals(client.getTokenSettings().getAccessTokenFormat())) {
            throw new IllegalArgumentException("Only self-contained JWT access tokens are supported");
        }
        if (client.getTokenSettings().isReuseRefreshTokens()) {
            throw new IllegalArgumentException("Refresh token rotation is mandatory");
        }
        if (!SignatureAlgorithm.RS256.equals(client.getTokenSettings().getIdTokenSignatureAlgorithm())) {
            throw new IllegalArgumentException("Only RS256 ID token signatures are supported");
        }
    }

    private boolean isPublicClient(RegisteredClient client) {
        return client.getClientAuthenticationMethods().size() == 1
                && client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE);
    }

    private static UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException(field + " must be a UUID", exception);
        }
    }

    private static long seconds(Duration duration) {
        return duration.toSeconds();
    }

    private static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM does not provide SHA-256", exception);
        }
    }

    private record ClientRow(
            UUID id,
            String clientId,
            Instant clientIdIssuedAt,
            String clientSecretHash,
            Instant clientSecretExpiresAt,
            String clientName,
            boolean requirePkce,
            boolean requireConsent,
            long authorizationCodeTtlSeconds,
            long accessTokenTtlSeconds,
            long refreshTokenTtlSeconds,
            boolean reuseRefreshTokens,
            String idTokenSignatureAlgorithm
    ) {
    }
}
