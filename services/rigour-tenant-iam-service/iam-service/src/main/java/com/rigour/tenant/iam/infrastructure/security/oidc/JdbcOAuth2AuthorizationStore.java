package com.rigour.tenant.iam.infrastructure.security.oidc;

import com.rigour.tenant.iam.application.port.out.IdentifierGenerator;
import com.rigour.tenant.iam.infrastructure.persistence.UuidBinaryCodec;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.jackson.SecurityJacksonModules;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType;
import org.springframework.security.oauth2.core.OAuth2DeviceCode;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2UserCode;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;

/**
 * Authorization Code与Refresh Token轮换阶段的OAuth授权存储。
 * State与Code只保存SHA-256哈希，授权请求和已擦除凭据的主体使用AES-GCM密文保存。
 * Access Token和ID Token为自包含JWT不落库；Refresh Token只保存SHA-256哈希并检测重放。
 */
public final class JdbcOAuth2AuthorizationStore implements OAuth2AuthorizationService {

    private static final OAuth2TokenType STATE_TOKEN_TYPE = new OAuth2TokenType(OAuth2ParameterNames.STATE);
    private static final OAuth2TokenType CODE_TOKEN_TYPE = new OAuth2TokenType(OAuth2ParameterNames.CODE);
    private static final OAuth2TokenType ID_TOKEN_TYPE = new OAuth2TokenType(OidcParameterNames.ID_TOKEN);
    private static final String SOURCE_REFRESH_TOKEN_ID =
            JdbcOAuth2AuthorizationStore.class.getName() + ".sourceRefreshTokenId";
    private static final TypeReference<Map<String, Object>> ATTRIBUTES_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final RegisteredClientRepository registeredClientRepository;
    private final AuthorizationAttributesCipher attributesCipher;
    private final AuthorizationSessionResolver sessionResolver;
    private final TransactionTemplate transactionTemplate;
    private final IdentifierGenerator identifierGenerator;
    private final SignedIdTokenDecoder idTokenDecoder;
    private final JsonMapper jsonMapper;
    private final JsonMapper plainJsonMapper;

    public JdbcOAuth2AuthorizationStore(
            JdbcTemplate jdbcTemplate,
            RegisteredClientRepository registeredClientRepository,
            PlatformTransactionManager transactionManager,
            AuthorizationAttributesCipher attributesCipher,
            AuthorizationSessionResolver sessionResolver,
            IdentifierGenerator identifierGenerator,
            SignedIdTokenDecoder idTokenDecoder
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate cannot be null");
        this.registeredClientRepository = Objects.requireNonNull(
                registeredClientRepository, "registeredClientRepository cannot be null");
        this.attributesCipher = Objects.requireNonNull(attributesCipher, "attributesCipher cannot be null");
        this.sessionResolver = Objects.requireNonNull(sessionResolver, "sessionResolver cannot be null");
        this.identifierGenerator = Objects.requireNonNull(identifierGenerator, "identifierGenerator cannot be null");
        this.idTokenDecoder = idTokenDecoder;
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager cannot be null"));
        List<JacksonModule> modules = SecurityJacksonModules.getModules(getClass().getClassLoader());
        this.jsonMapper = JsonMapper.builder().addModules(modules).build();
        this.plainJsonMapper = JsonMapper.builder().build();
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        validateAuthorization(authorization);
        Boolean replayDetected = transactionTemplate.execute(status -> saveInTransaction(authorization));
        if (Boolean.TRUE.equals(replayDetected)) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
        }
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        Objects.requireNonNull(authorization, "authorization cannot be null");
        UUID id = parseUuid(authorization.getId(), "authorization.id");
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("""
                        UPDATE iam_oauth_authorization
                        SET status = 'REVOKED', revoked_at = ?, revoke_reason = 'AUTHORIZATION_REMOVED',
                            version = version + 1, updated_at = ?
                        WHERE id = ? AND status <> 'REVOKED'
                        """, utcNow(), utcNow(), UuidBinaryCodec.encode(id));
            revokeRefreshTokensForAuthorization(id, "AUTHORIZATION_REMOVED");
        });
    }

    @Override
    public OAuth2Authorization findById(String id) {
        UUID authorizationId = parseUuid(id, "authorization.id");
        AuthorizationRow row = loadOne("authorization.id", "a.id = ?", UuidBinaryCodec.encode(authorizationId));
        return row == null ? null : toAuthorization(row, null, null);
    }

    @Override
    public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token cannot be empty");
        }
        if (tokenType == null) {
            OAuth2Authorization byState = findByToken(token, STATE_TOKEN_TYPE);
            if (byState != null) {
                return byState;
            }
            OAuth2Authorization byCode = findByToken(token, CODE_TOKEN_TYPE);
            return byCode != null ? byCode : findByToken(token, OAuth2TokenType.REFRESH_TOKEN);
        }
        byte[] hash = sha256(token);
        if (STATE_TOKEN_TYPE.equals(tokenType)) {
            AuthorizationRow row = loadOne("state", "a.state_hash = ?", hash);
            return row == null || !sessionIsActiveForPrincipal(row.sessionId(), row.principalName())
                    ? null : toAuthorization(row, token, null);
        }
        if (CODE_TOKEN_TYPE.equals(tokenType)) {
            AuthorizationRow row = loadOne("authorization code", "a.authorization_code_hash = ?", hash);
            return row == null || !sessionIsActiveForPrincipal(row.sessionId(), row.principalName())
                    ? null : toAuthorization(row, null, token);
        }
        if (OAuth2TokenType.REFRESH_TOKEN.equals(tokenType)) {
            return transactionTemplate.execute(status -> findByRefreshToken(hash, token));
        }
        if (ID_TOKEN_TYPE.equals(tokenType)) {
            return findBySelfContainedIdToken(token);
        }
        return null;
    }

    private OAuth2Authorization findBySelfContainedIdToken(String rawToken) {
        if (idTokenDecoder == null) {
            return null;
        }
        Jwt jwt;
        try {
            jwt = idTokenDecoder.decode(rawToken);
        } catch (RuntimeException exception) {
            return null;
        }
        if (!"id".equals(jwt.getClaimAsString("tokenUse")) || jwt.getAudience().size() != 1) {
            return null;
        }
        RegisteredClient client = registeredClientRepository.findByClientId(jwt.getAudience().getFirst());
        if (client == null) {
            return null;
        }
        UUID sessionId;
        try {
            sessionId = UUID.fromString(jwt.getClaimAsString("sessionId"));
        } catch (IllegalArgumentException exception) {
            return null;
        }
        List<AuthorizationRow> rows = jdbcTemplate.query("""
                        SELECT a.id, a.client_id, a.session_id, a.principal_name, a.grant_type,
                               a.authorized_scopes_json, a.attributes_ciphertext, a.attributes_key_version,
                               a.code_issued_at, a.code_expires_at, a.code_consumed_at, a.status
                          FROM iam_oauth_authorization a
                         WHERE a.session_id = ? AND a.client_id = ? AND a.status = 'ACTIVE'
                         ORDER BY a.updated_at DESC
                         LIMIT 1
                        """, this::mapAuthorizationRow, UuidBinaryCodec.encode(sessionId),
                UuidBinaryCodec.encode(UUID.fromString(client.getId())));
        if (rows.isEmpty() || !rows.getFirst().principalName().equals(jwt.getSubject())
                || !sessionIsActiveForPrincipal(sessionId, jwt.getSubject())) {
            return null;
        }
        AuthorizationRow row = rows.getFirst();
        OAuth2Authorization base = toAuthorization(row, null, null);
        OidcIdToken idToken = new OidcIdToken(rawToken, jwt.getIssuedAt(), jwt.getExpiresAt(), jwt.getClaims());
        return OAuth2Authorization.from(base)
                .token(idToken, metadata -> metadata.put(
                        OAuth2Authorization.Token.CLAIMS_METADATA_NAME, jwt.getClaims()))
                .build();
    }

    private boolean saveInTransaction(OAuth2Authorization authorization) {
        UUID id = parseUuid(authorization.getId(), "authorization.id");
        UUID clientId = parseUuid(authorization.getRegisteredClientId(), "authorization.registeredClientId");
        ExistingRow existing = findExisting(id);
        if (existing != null && "REVOKED".equals(existing.status())) {
            throw new IllegalStateException("Revoked OAuth authorization cannot be saved again");
        }

        UUID sessionId = existing == null
                ? Objects.requireNonNull(sessionResolver.resolveSessionId(authorization),
                        "Authorization session resolver returned null")
                : existing.sessionId();
        if (!sessionIsActiveForPrincipal(sessionId, authorization.getPrincipalName())) {
            throw new IllegalStateException("OAuth authorization requires a matching active IAM session");
        }

        UUID sourceRefreshTokenId = sourceRefreshTokenId(authorization);
        if (sourceRefreshTokenId != null) {
            RefreshRotationState rotationState = lockRefreshTokenForRotation(sourceRefreshTokenId, sessionId, id);
            if (rotationState != RefreshRotationState.ACTIVE) {
                if (rotationState == RefreshRotationState.REPLAY) {
                    revokeSessionForReplay(sessionId);
                }
                return true;
            }
        }

        if (hasInvalidatedSelfContainedToken(authorization)) {
            revokeSessionForReplay(sessionId);
            return false;
        }

        OAuth2Authorization.Token<OAuth2AuthorizationCode> codeHolder = authorization
                .getToken(OAuth2AuthorizationCode.class);
        OAuth2AuthorizationCode code = codeHolder == null ? null : codeHolder.getToken();
        byte[] codeHash = code == null ? existingCodeHash(existing) : sha256(code.getTokenValue());
        Instant codeIssuedAt = code == null ? existingCodeIssuedAt(existing) : code.getIssuedAt();
        Instant codeExpiresAt = code == null ? existingCodeExpiresAt(existing) : code.getExpiresAt();
        Instant codeConsumedAt = existingCodeConsumedAt(existing);
        if (codeHolder != null && codeHolder.isInvalidated() && codeConsumedAt == null) {
            codeConsumedAt = consumedAt(codeIssuedAt, codeExpiresAt);
        }

        String state = authorization.getAttribute(OAuth2ParameterNames.STATE);
        byte[] stateHash = state == null || state.isBlank() ? null : sha256(state);
        Map<String, Object> encryptedAttributes = new HashMap<>(authorization.getAttributes());
        encryptedAttributes.remove(OAuth2ParameterNames.STATE);
        encryptedAttributes.remove(SOURCE_REFRESH_TOKEN_ID);
        requireErasedCredentials(encryptedAttributes);
        AuthorizationAttributesCipher.EncryptedAttributes encrypted = attributesCipher.encrypt(
                writeJsonBytes(encryptedAttributes), aad(id));
        boolean issuedTokens = authorization.getAccessToken() != null
                || authorization.getRefreshToken() != null
                || authorization.getToken(OidcIdToken.class) != null;
        String status = status(codeHash, codeExpiresAt, codeConsumedAt, issuedTokens);
        LocalDateTime now = utcNow();

        if (existing == null) {
            jdbcTemplate.update("""
                            INSERT INTO iam_oauth_authorization (
                                id, client_id, session_id, principal_name, grant_type,
                                authorized_scopes_json, state_hash, attributes_ciphertext,
                                attributes_key_version, authorization_code_hash, code_issued_at,
                                code_expires_at, code_consumed_at, status, revoked_at, revoke_reason,
                                version, created_at, updated_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, 0, ?, ?)
                            """,
                    UuidBinaryCodec.encode(id), UuidBinaryCodec.encode(clientId), UuidBinaryCodec.encode(sessionId),
                    authorization.getPrincipalName(), authorization.getAuthorizationGrantType().getValue(),
                    writeJsonString(authorization.getAuthorizedScopes()), stateHash, encrypted.ciphertext(),
                    encrypted.keyVersion(), codeHash, utc(codeIssuedAt), utc(codeExpiresAt), utc(codeConsumedAt),
                    status, now, now);
        } else {
            jdbcTemplate.update("""
                            UPDATE iam_oauth_authorization
                            SET client_id = ?, session_id = ?, principal_name = ?, grant_type = ?,
                                authorized_scopes_json = ?, state_hash = ?, attributes_ciphertext = ?,
                                attributes_key_version = ?, authorization_code_hash = ?, code_issued_at = ?,
                                code_expires_at = ?, code_consumed_at = ?, status = ?, revoked_at = NULL,
                                revoke_reason = NULL, version = version + 1, updated_at = ?
                            WHERE id = ? AND status <> 'REVOKED'
                            """,
                    UuidBinaryCodec.encode(clientId), UuidBinaryCodec.encode(sessionId),
                    authorization.getPrincipalName(), authorization.getAuthorizationGrantType().getValue(),
                    writeJsonString(authorization.getAuthorizedScopes()), stateHash, encrypted.ciphertext(),
                    encrypted.keyVersion(), codeHash, utc(codeIssuedAt), utc(codeExpiresAt), utc(codeConsumedAt),
                    status, now, UuidBinaryCodec.encode(id));
        }

        synchronizeRefreshToken(authorization, id, sessionId, sourceRefreshTokenId, now);
        return false;
    }

    private OAuth2Authorization findByRefreshToken(byte[] tokenHash, String rawToken) {
        List<RefreshTokenRow> rows = jdbcTemplate.query("""
                        SELECT r.id, r.authorization_id, r.session_id, r.issued_at, r.expires_at,
                               r.consumed_at, r.revoked_at, a.principal_name, a.status AS authorization_status
                          FROM iam_refresh_token r
                          JOIN iam_oauth_authorization a ON a.id = r.authorization_id
                         WHERE r.token_hash = ?
                         FOR UPDATE
                        """, (resultSet, rowNumber) -> new RefreshTokenRow(
                        UuidBinaryCodec.decode(resultSet.getBytes("id")),
                        UuidBinaryCodec.decode(resultSet.getBytes("authorization_id")),
                        UuidBinaryCodec.decode(resultSet.getBytes("session_id")),
                        instant(resultSet.getObject("issued_at", LocalDateTime.class)),
                        instant(resultSet.getObject("expires_at", LocalDateTime.class)),
                        instant(resultSet.getObject("consumed_at", LocalDateTime.class)),
                        instant(resultSet.getObject("revoked_at", LocalDateTime.class)),
                        resultSet.getString("principal_name"),
                        resultSet.getString("authorization_status")), tokenHash);
        if (rows.isEmpty()) {
            return null;
        }
        RefreshTokenRow refresh = rows.getFirst();
        if (refresh.consumedAt() != null || refresh.revokedAt() != null) {
            revokeSessionForReplay(refresh.sessionId());
            return null;
        }
        if (!refresh.expiresAt().isAfter(Instant.now())) {
            jdbcTemplate.update("""
                            UPDATE iam_refresh_token
                               SET revoked_at = ?, revoke_reason = 'REFRESH_TOKEN_EXPIRED'
                             WHERE id = ? AND revoked_at IS NULL
                            """, utcNow(), UuidBinaryCodec.encode(refresh.id()));
            return null;
        }
        if (!"ACTIVE".equals(refresh.authorizationStatus())
                || !sessionIsActiveForPrincipal(refresh.sessionId(), refresh.principalName())) {
            return null;
        }
        AuthorizationRow authorization = loadOne(
                "refresh authorization", "a.id = ?", UuidBinaryCodec.encode(refresh.authorizationId()));
        return authorization == null ? null : toAuthorization(authorization, null, null, rawToken, refresh);
    }

    private RefreshRotationState lockRefreshTokenForRotation(
            UUID refreshTokenId, UUID sessionId, UUID authorizationId) {
        List<RefreshRotationRow> rows = jdbcTemplate.query("""
                        SELECT session_id, authorization_id, expires_at, consumed_at, revoked_at
                          FROM iam_refresh_token
                         WHERE id = ?
                         FOR UPDATE
                        """, (resultSet, rowNumber) -> new RefreshRotationRow(
                        UuidBinaryCodec.decode(resultSet.getBytes("session_id")),
                        UuidBinaryCodec.decode(resultSet.getBytes("authorization_id")),
                        instant(resultSet.getObject("expires_at", LocalDateTime.class)),
                        instant(resultSet.getObject("consumed_at", LocalDateTime.class)),
                        instant(resultSet.getObject("revoked_at", LocalDateTime.class))),
                UuidBinaryCodec.encode(refreshTokenId));
        if (rows.isEmpty()) {
            return RefreshRotationState.INVALID;
        }
        RefreshRotationRow row = rows.getFirst();
        if (!sessionId.equals(row.sessionId()) || !authorizationId.equals(row.authorizationId())) {
            return RefreshRotationState.INVALID;
        }
        if (row.consumedAt() != null || row.revokedAt() != null) {
            return RefreshRotationState.REPLAY;
        }
        return row.expiresAt().isAfter(Instant.now())
                ? RefreshRotationState.ACTIVE : RefreshRotationState.INVALID;
    }

    private void synchronizeRefreshToken(
            OAuth2Authorization authorization,
            UUID authorizationId,
            UUID sessionId,
            UUID sourceRefreshTokenId,
            LocalDateTime now
    ) {
        OAuth2Authorization.Token<OAuth2RefreshToken> holder = authorization.getRefreshToken();
        if (holder == null) {
            return;
        }
        OAuth2RefreshToken refreshToken = holder.getToken();
        UUID newTokenId = identifierGenerator.nextId();
        byte[] tokenHash = sha256(refreshToken.getTokenValue());
        jdbcTemplate.update("""
                        INSERT INTO iam_refresh_token (
                            id, session_id, authorization_id, token_hash, issued_at, expires_at,
                            consumed_at, replaced_by_id, revoked_at, revoke_reason, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, ?)
                        """, UuidBinaryCodec.encode(newTokenId), UuidBinaryCodec.encode(sessionId),
                UuidBinaryCodec.encode(authorizationId), tokenHash, utc(refreshToken.getIssuedAt()),
                utc(refreshToken.getExpiresAt()), now);
        if (sourceRefreshTokenId != null) {
            int updated = jdbcTemplate.update("""
                            UPDATE iam_refresh_token
                               SET consumed_at = ?, replaced_by_id = ?
                             WHERE id = ? AND consumed_at IS NULL AND revoked_at IS NULL
                            """, now, UuidBinaryCodec.encode(newTokenId), UuidBinaryCodec.encode(sourceRefreshTokenId));
            if (updated != 1) {
                throw new IllegalStateException("Refresh token changed during rotation");
            }
            jdbcTemplate.update("""
                            UPDATE iam_auth_session
                               SET version = version + 1, last_seen_at = ?
                             WHERE id = ? AND status = 'ACTIVE' AND expires_at > ?
                            """, now, UuidBinaryCodec.encode(sessionId), now);
        }
    }

    private UUID sourceRefreshTokenId(OAuth2Authorization authorization) {
        String value = authorization.getAttribute(SOURCE_REFRESH_TOKEN_ID);
        return value == null ? null : parseUuid(value, "source refresh token id");
    }

    private boolean hasInvalidatedSelfContainedToken(OAuth2Authorization authorization) {
        OAuth2Authorization.Token<OAuth2AccessToken> accessToken = authorization.getAccessToken();
        OAuth2Authorization.Token<OidcIdToken> idToken = authorization.getToken(OidcIdToken.class);
        OAuth2Authorization.Token<OAuth2RefreshToken> refreshToken = authorization.getRefreshToken();
        return (accessToken != null && accessToken.isInvalidated())
                || (idToken != null && idToken.isInvalidated())
                || (refreshToken != null && refreshToken.isInvalidated());
    }

    private void revokeSessionForReplay(UUID sessionId) {
        LocalDateTime now = utcNow();
        jdbcTemplate.update("""
                        UPDATE iam_auth_session
                           SET status = 'REVOKED', revoked_at = ?, revoke_reason = 'TOKEN_REPLAY', version = version + 1
                         WHERE id = ? AND status = 'ACTIVE'
                        """, now, UuidBinaryCodec.encode(sessionId));
        jdbcTemplate.update("""
                        UPDATE iam_oauth_authorization
                           SET status = 'REVOKED', revoked_at = ?, revoke_reason = 'TOKEN_REPLAY',
                               version = version + 1, updated_at = ?
                         WHERE session_id = ? AND status <> 'REVOKED'
                        """, now, now, UuidBinaryCodec.encode(sessionId));
        jdbcTemplate.update("""
                        UPDATE iam_refresh_token
                           SET revoked_at = COALESCE(revoked_at, ?), revoke_reason = 'TOKEN_REPLAY'
                         WHERE session_id = ?
                        """, now, UuidBinaryCodec.encode(sessionId));
    }

    private void revokeRefreshTokensForAuthorization(UUID authorizationId, String reason) {
        jdbcTemplate.update("""
                        UPDATE iam_refresh_token
                           SET revoked_at = COALESCE(revoked_at, ?), revoke_reason = ?
                         WHERE authorization_id = ?
                        """, utcNow(), reason, UuidBinaryCodec.encode(authorizationId));
    }

    private AuthorizationRow loadOne(String lookupName, String predicate, Object value) {
        List<AuthorizationRow> rows = jdbcTemplate.query("""
                        SELECT a.id, a.client_id, a.session_id, a.principal_name, a.grant_type,
                               a.authorized_scopes_json, a.attributes_ciphertext, a.attributes_key_version,
                               a.code_issued_at, a.code_expires_at, a.code_consumed_at, a.status
                        FROM iam_oauth_authorization a
                        WHERE %s AND a.status <> 'REVOKED'
                        """.formatted(predicate), this::mapAuthorizationRow, value);
        if (rows.size() > 1) {
            throw new DataRetrievalFailureException("Multiple OAuth authorizations matched " + lookupName);
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private AuthorizationRow mapAuthorizationRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AuthorizationRow(
                UuidBinaryCodec.decode(resultSet.getBytes("id")),
                UuidBinaryCodec.decode(resultSet.getBytes("client_id")),
                UuidBinaryCodec.decode(resultSet.getBytes("session_id")),
                resultSet.getString("principal_name"),
                resultSet.getString("grant_type"),
                resultSet.getString("authorized_scopes_json"),
                resultSet.getBytes("attributes_ciphertext"),
                resultSet.getString("attributes_key_version"),
                instant(resultSet.getObject("code_issued_at", LocalDateTime.class)),
                instant(resultSet.getObject("code_expires_at", LocalDateTime.class)),
                instant(resultSet.getObject("code_consumed_at", LocalDateTime.class)),
                resultSet.getString("status")
        );
    }

    private OAuth2Authorization toAuthorization(AuthorizationRow row, String rawState, String rawCode) {
        return toAuthorization(row, rawState, rawCode, null, null);
    }

    private OAuth2Authorization toAuthorization(
            AuthorizationRow row,
            String rawState,
            String rawCode,
            String rawRefreshToken,
            RefreshTokenRow refreshTokenRow
    ) {
        RegisteredClient registeredClient = registeredClientRepository.findById(row.clientId().toString());
        if (registeredClient == null) {
            throw new DataRetrievalFailureException(
                    "Registered OAuth client not found for authorization " + row.id());
        }
        Map<String, Object> attributes = readAttributes(row);
        OAuth2Authorization.Builder builder = OAuth2Authorization.withRegisteredClient(registeredClient)
                .id(row.id().toString())
                .principalName(row.principalName())
                .authorizationGrantType(new AuthorizationGrantType(row.grantType()))
                .authorizedScopes(readStringSet(row.authorizedScopesJson()))
                .attributes(target -> target.putAll(attributes));
        if (rawState != null) {
            builder.attribute(OAuth2ParameterNames.STATE, rawState);
        }
        if (rawCode != null && row.codeIssuedAt() != null && row.codeExpiresAt() != null) {
            OAuth2AuthorizationCode code = new OAuth2AuthorizationCode(
                    rawCode, row.codeIssuedAt(), row.codeExpiresAt());
            builder.token(code, metadata -> metadata.put(
                    OAuth2Authorization.Token.INVALIDATED_METADATA_NAME, row.codeConsumedAt() != null));
        }
        if (rawRefreshToken != null && refreshTokenRow != null) {
            builder.refreshToken(new OAuth2RefreshToken(
                    rawRefreshToken, refreshTokenRow.issuedAt(), refreshTokenRow.expiresAt()));
            builder.attribute(SOURCE_REFRESH_TOKEN_ID, refreshTokenRow.id().toString());
        } else if ("ACTIVE".equals(row.status())) {
            Instant issuedAt = row.codeConsumedAt() == null ? Instant.now() : row.codeConsumedAt();
            builder.accessToken(new OAuth2AccessToken(
                    TokenType.BEARER,
                    "redacted-self-contained-token",
                    issuedAt,
                    issuedAt.plusSeconds(1),
                    readStringSet(row.authorizedScopesJson())));
        }
        return builder.build();
    }

    private ExistingRow findExisting(UUID id) {
        List<ExistingRow> rows = jdbcTemplate.query("""
                        SELECT session_id, authorization_code_hash, code_issued_at,
                               code_expires_at, code_consumed_at, status
                        FROM iam_oauth_authorization WHERE id = ?
                        """, (resultSet, rowNumber) -> new ExistingRow(
                        UuidBinaryCodec.decode(resultSet.getBytes("session_id")),
                        resultSet.getBytes("authorization_code_hash"),
                        instant(resultSet.getObject("code_issued_at", LocalDateTime.class)),
                        instant(resultSet.getObject("code_expires_at", LocalDateTime.class)),
                        instant(resultSet.getObject("code_consumed_at", LocalDateTime.class)),
                        resultSet.getString("status")), UuidBinaryCodec.encode(id));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private boolean sessionIsActiveForPrincipal(UUID sessionId, String principalName) {
        UUID principalId = parseUuid(principalName, "authorization.principalName");
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM iam_auth_session
                        WHERE id = ? AND principal_id = ? AND status = 'ACTIVE' AND expires_at > ?
                        """, Integer.class, UuidBinaryCodec.encode(sessionId),
                UuidBinaryCodec.encode(principalId), utcNow());
        return count != null && count == 1;
    }

    private void validateAuthorization(OAuth2Authorization authorization) {
        Objects.requireNonNull(authorization, "authorization cannot be null");
        if (!AuthorizationGrantType.AUTHORIZATION_CODE.equals(authorization.getAuthorizationGrantType())) {
            throw new IllegalArgumentException("Only authorization_code grant persistence is supported");
        }
        if (authorization.getToken(OAuth2UserCode.class) != null
                || authorization.getToken(OAuth2DeviceCode.class) != null) {
            throw new IllegalArgumentException("Device authorization tokens are not supported");
        }
    }

    private void requireErasedCredentials(Map<String, Object> attributes) {
        Object principal = attributes.get(Principal.class.getName());
        if (!(principal instanceof Authentication authentication) || !authentication.isAuthenticated()) {
            throw new IllegalArgumentException("OAuth authorization requires an authenticated principal attribute");
        }
        Object credentials = authentication.getCredentials();
        if (credentials != null && (!(credentials instanceof String text) || !text.isEmpty())) {
            throw new IllegalArgumentException("Authenticated principal credentials must be erased before persistence");
        }
    }

    private Map<String, Object> readAttributes(AuthorizationRow row) {
        try {
            byte[] plaintext = attributesCipher.decrypt(
                    row.attributesKeyVersion(), row.attributesCiphertext(), aad(row.id()));
            return jsonMapper.readValue(plaintext, ATTRIBUTES_TYPE);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new DataRetrievalFailureException("Cannot deserialize encrypted OAuth authorization attributes",
                    exception);
        }
    }

    private byte[] writeJsonBytes(Map<String, Object> attributes) {
        try {
            return jsonMapper.writeValueAsBytes(attributes);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Cannot serialize OAuth authorization attributes", exception);
        }
    }

    private String writeJsonString(Set<String> values) {
        try {
            return plainJsonMapper.writeValueAsString(values.stream().sorted().toList());
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Cannot serialize OAuth authorized scopes", exception);
        }
    }

    private Set<String> readStringSet(String json) {
        try {
            return new LinkedHashSet<>(plainJsonMapper.readValue(json, STRING_LIST_TYPE));
        } catch (JacksonException exception) {
            throw new DataRetrievalFailureException("Cannot deserialize OAuth authorized scopes", exception);
        }
    }

    private static String status(
            byte[] codeHash,
            Instant codeExpiresAt,
            Instant codeConsumedAt,
            boolean issuedTokens
    ) {
        if (issuedTokens || codeConsumedAt != null) {
            return "ACTIVE";
        }
        if (codeHash == null) {
            return "PENDING";
        }
        return codeExpiresAt != null && Instant.now().isAfter(codeExpiresAt) ? "EXPIRED" : "CODE_ISSUED";
    }

    private static Instant consumedAt(Instant issuedAt, Instant expiresAt) {
        Instant now = Instant.now();
        if (expiresAt != null && now.isAfter(expiresAt)) {
            return expiresAt;
        }
        if (issuedAt != null && now.isBefore(issuedAt)) {
            return issuedAt;
        }
        return now;
    }

    private static byte[] existingCodeHash(ExistingRow existing) {
        return existing == null ? null : existing.codeHash();
    }

    private static Instant existingCodeIssuedAt(ExistingRow existing) {
        return existing == null ? null : existing.codeIssuedAt();
    }

    private static Instant existingCodeExpiresAt(ExistingRow existing) {
        return existing == null ? null : existing.codeExpiresAt();
    }

    private static Instant existingCodeConsumedAt(ExistingRow existing) {
        return existing == null ? null : existing.codeConsumedAt();
    }

    private static UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException(field + " must be a UUID", exception);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM does not provide SHA-256", exception);
        }
    }

    private static byte[] aad(UUID authorizationId) {
        return ("iam_oauth_authorization:" + authorizationId).getBytes(StandardCharsets.UTF_8);
    }

    private static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utcNow() {
        return utc(Instant.now());
    }

    private record ExistingRow(
            UUID sessionId,
            byte[] codeHash,
            Instant codeIssuedAt,
            Instant codeExpiresAt,
            Instant codeConsumedAt,
            String status
    ) {
    }

    private record AuthorizationRow(
            UUID id,
            UUID clientId,
            UUID sessionId,
            String principalName,
            String grantType,
            String authorizedScopesJson,
            byte[] attributesCiphertext,
            String attributesKeyVersion,
            Instant codeIssuedAt,
            Instant codeExpiresAt,
            Instant codeConsumedAt,
            String status
    ) {
    }

    private record RefreshTokenRow(
            UUID id,
            UUID authorizationId,
            UUID sessionId,
            Instant issuedAt,
            Instant expiresAt,
            Instant consumedAt,
            Instant revokedAt,
            String principalName,
            String authorizationStatus
    ) {
    }

    private record RefreshRotationRow(
            UUID sessionId,
            UUID authorizationId,
            Instant expiresAt,
            Instant consumedAt,
            Instant revokedAt
    ) {
    }

    private enum RefreshRotationState {
        ACTIVE,
        REPLAY,
        INVALID
    }
}
