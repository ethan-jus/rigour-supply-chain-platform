package com.rigour.sales.temporarycheckin;

import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.IdentityVerifyRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinModels.SalesIdentityView;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.RiskHistory;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRepository.SalespersonRow;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 临时打卡的轻量销售身份与风险采集。个人码不会被记录或回传；
 * IP 不被当作身份或拦截依据，只保存服务端 HMAC、网段 HMAC 和掩码摘要。
 */
@Service
@ConditionalOnProperty(prefix = "rigour.sales.temporary-checkin", name = "enabled", havingValue = "true")
class TemporaryCheckinSalesIdentityService {

    static final String DEVICE_COOKIE = "__Host-rigour-sales-checkin-device";
    static final String IDENTITY_COOKIE = "__Host-rigour-sales-checkin-identity";

    private static final String KDF_PREFIX = "pbkdf2-sha256";
    private static final String COOKIE_VERSION = "v1";
    private static final int HASH_BYTES = 32;
    private static final int SALT_BYTES = 16;
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(2);
    private static final char[] TEMPORARY_CODE_ALPHABET =
            "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();

    private final TemporaryCheckinRepository repository;
    private final TemporaryCheckinProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final UUID tenantId;
    private final byte[] signingKey;
    private final byte[] riskKey;
    private final byte[] proxyMarker;
    private final String dummyCredentialHash;

    TemporaryCheckinSalesIdentityService(
            TemporaryCheckinRepository repository,
            TemporaryCheckinProperties properties,
            Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
        this.tenantId = properties.requireTenantId();
        validateNumbers(properties);
        this.signingKey = configuredKey(properties.getIdentitySigningKeyBase64(),
                "identity-signing-key-base64", properties.isIdentityEnforcementEnabled());
        this.riskKey = configuredKey(properties.getRiskHmacKeyBase64(),
                "risk-hmac-key-base64", properties.isIdentityEnforcementEnabled());
        this.proxyMarker = configuredMarker(properties.getTrustedProxyMarker(),
                properties.isIdentityEnforcementEnabled());
        this.dummyCredentialHash = KDF_PREFIX + "$" + properties.getCredentialPbkdf2Iterations()
                + "$" + Base64.getEncoder().encodeToString(new byte[SALT_BYTES])
                + "$" + Base64.getEncoder().encodeToString(new byte[HASH_BYTES]);
    }

    IdentityVerification verify(IdentityVerifyRequest request, TemporaryCheckinRequestFacts requestFacts) {
        requireConfiguredIdentity();
        RequestRiskFacts riskFacts = trustedRiskFacts(requestFacts);
        if (request == null || request.salespersonId() == null) {
            throw TemporaryCheckinException.badRequest("请选择销售并输入个人打卡码");
        }
        String city = normalizedRequired(request.city(), "请选择城市");
        String personalCode = normalizedCode(request.personalCode());
        SalespersonRow salesperson = activeSalesperson(request.salespersonId());
        if (!city.equals(salesperson.city())) {
            throw TemporaryCheckinException.forbiddenIdentity("销售身份验证失败");
        }
        if (salesperson.checkinSecretHash() == null || salesperson.checkinSecretHash().isBlank()) {
            verifyCredential(personalCode, dummyCredentialHash);
            throw TemporaryCheckinException.conflict("该销售尚未设置个人打卡码，请联系城市管理员");
        }
        if (!verifyCredential(personalCode, salesperson.checkinSecretHash())) {
            throw TemporaryCheckinException.forbiddenIdentity("销售身份验证失败");
        }

        Instant now = clock.instant();
        Instant expiresAt = now.plus(Duration.ofDays(properties.getIdentityTtlDays()));
        DeviceToken device = parseDevice(requestFacts.deviceCookie());
        if (device == null) device = issueDevice();
        String deviceHash = deviceHash(device.nonce());
        riskFacts = riskFacts.withDevice(deviceHash);
        String identityToken = issueIdentityToken(
                salesperson.id(), salesperson.credentialVersion(), deviceHash, now, expiresAt);
        SalesIdentityView view = identityView(salesperson, expiresAt, true);
        return new IdentityVerification(view, deviceCookie(device), identityCookie(identityToken),
                new AuthorizedRequest(salesperson, "PERSONAL_CODE", now, expiresAt, deviceHash, riskFacts));
    }

    SalesIdentityView current(TemporaryCheckinRequestFacts requestFacts) {
        if (!properties.isIdentityEnforcementEnabled()) {
            return new SalesIdentityView(false, null, null, null, null, false);
        }
        return requireCurrent(requestFacts).view();
    }

    AuthorizedRequest requireSalesperson(UUID requestedSalespersonId, TemporaryCheckinRequestFacts requestFacts) {
        if (requestedSalespersonId == null) {
            throw TemporaryCheckinException.badRequest("salespersonId不能为空");
        }
        if (!properties.isIdentityEnforcementEnabled()) {
            SalespersonRow salesperson = activeSalesperson(requestedSalespersonId);
            return new AuthorizedRequest(salesperson, "LEGACY_ANONYMOUS", null, null,
                    legacyDeviceHash(requestFacts), optionalRiskFacts(requestFacts));
        }
        AuthorizedRequest authorized = requireCurrent(requestFacts);
        if (!MessageDigest.isEqual(
                authorized.salesperson().id().toString().getBytes(StandardCharsets.US_ASCII),
                requestedSalespersonId.toString().getBytes(StandardCharsets.US_ASCII))) {
            throw TemporaryCheckinException.forbiddenIdentity("当前设备已绑定其他销售，请先切换身份");
        }
        return authorized;
    }

    AuthorizedRequest requireSubmission(
            UUID salespersonId,
            String submissionDeviceHash,
            TemporaryCheckinRequestFacts requestFacts) {
        AuthorizedRequest authorized = requireSalesperson(salespersonId, requestFacts);
        if (properties.isIdentityEnforcementEnabled()
                && submissionDeviceHash != null
                && !constantEquals(submissionDeviceHash, authorized.deviceTokenHash())) {
            throw TemporaryCheckinException.forbiddenIdentity("该草稿已绑定另一台设备，请回到原设备继续提交");
        }
        return authorized;
    }

    RiskSnapshot evaluateRisk(AuthorizedRequest authorized) {
        RequestRiskFacts facts = authorized.requestFacts();
        if (facts == null || facts.deviceTokenHash() == null || facts.ipHash() == null) {
            return new RiskSnapshot("NONE", List.of(), facts, clock.instant());
        }
        Instant now = clock.instant();
        RiskHistory history = repository.findRiskHistory(
                tenantId, authorized.salesperson().id(), facts.deviceTokenHash(),
                facts.ipHash(), facts.ipNetworkHash(), now);
        long deviceSalespersons = history.deviceSalespersonCount()
                + (history.deviceSeenForSalesperson() ? 0 : 1);
        long salespersonDevices = history.salespersonDeviceCount()
                + (history.salespersonSeenDevice() ? 0 : 1);
        long salespersonNetworks = history.salespersonNetworkCount()
                + (history.salespersonSeenNetwork() ? 0 : 1);
        long sharedIpSalespersons = history.ipSalespersonCount()
                + (history.ipSeenForSalesperson() ? 0 : 1);

        List<String> flags = new ArrayList<>();
        String level = "NONE";
        if (deviceSalespersons > 1) {
            flags.add("DEVICE_MULTIPLE_SALES");
            level = "HIGH";
        }
        if (salespersonDevices >= properties.getRiskDevicesPerDay()) {
            flags.add("SALESPERSON_MULTIPLE_DEVICES");
            level = maxRisk(level, "MEDIUM");
        }
        if (salespersonNetworks >= properties.getRiskIpNetworksPerDay()) {
            flags.add("SALESPERSON_IP_CHURN");
            level = maxRisk(level, "MEDIUM");
        }
        if (sharedIpSalespersons >= 3) {
            flags.add("SHARED_IP_MULTIPLE_SALES");
            level = maxRisk(level, "LOW");
        }
        return new RiskSnapshot(level, List.copyOf(flags), facts, now);
    }

    void requireTrustedProxy(TemporaryCheckinRequestFacts facts) {
        if (!properties.isIdentityEnforcementEnabled()) return;
        trustedRiskFacts(facts);
    }

    ResponseCookie clearIdentityCookie(TemporaryCheckinRequestFacts facts) {
        requireTrustedProxy(facts);
        return ResponseCookie.from(IDENTITY_COOKIE, "")
                .secure(true).httpOnly(true).sameSite("Strict").path("/")
                .maxAge(Duration.ZERO).build();
    }

    /** 供受保护的治理服务生成一次性可见的个人码，明文不入库。 */
    @Transactional
    IssuedTemporaryCode issueTemporaryCode(
            UUID requestedTenantId, UUID salespersonId, String actor, String reason) {
        if (!tenantId.equals(requestedTenantId) || salespersonId == null) {
            throw TemporaryCheckinException.notFound("销售不存在或已停用");
        }
        String normalizedActor = limited(actor, 128, "操作人不能为空");
        String normalizedReason = limited(reason, 512, "请填写重置原因");
        activeSalesperson(salespersonId);
        String code = randomTemporaryCode();
        String hash = encodePersonalCode(code);
        Instant now = clock.instant();
        int version = repository.rotateSalespersonCredential(
                tenantId, salespersonId, hash, normalizedActor, normalizedReason, now);
        if (version < 1) throw TemporaryCheckinException.conflict("销售个人码更新冲突，请重试");
        return new IssuedTemporaryCode(salespersonId, code, version, now);
    }

    String encodePersonalCode(String rawCode) {
        String code = normalizedCode(rawCode);
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = pbkdf2(code.toCharArray(), salt, properties.getCredentialPbkdf2Iterations());
        return KDF_PREFIX + "$" + properties.getCredentialPbkdf2Iterations()
                + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(hash);
    }

    private AuthorizedRequest requireCurrent(TemporaryCheckinRequestFacts requestFacts) {
        requireConfiguredIdentity();
        RequestRiskFacts facts = trustedRiskFacts(requestFacts);
        DeviceToken device = parseDevice(requestFacts.deviceCookie());
        IdentityToken identity = parseIdentity(requestFacts.identityCookie());
        if (device == null || identity == null) {
            throw TemporaryCheckinException.unauthorizedIdentity("请先选择本人并输入个人打卡码");
        }
        String deviceHash = deviceHash(device.nonce());
        facts = facts.withDevice(deviceHash);
        Instant now = clock.instant();
        if (!constantEquals(deviceHash, identity.deviceHash())
                || identity.issuedAt().isAfter(now.plus(MAX_CLOCK_SKEW))
                || !identity.expiresAt().isAfter(now)) {
            throw TemporaryCheckinException.unauthorizedIdentity("销售身份已失效，请重新验证");
        }
        SalespersonRow salesperson = activeSalesperson(identity.salespersonId());
        if (salesperson.credentialVersion() != identity.credentialVersion()) {
            throw TemporaryCheckinException.unauthorizedIdentity("个人打卡码已更新，请重新验证");
        }
        return new AuthorizedRequest(salesperson, "PERSONAL_CODE", identity.issuedAt(), identity.expiresAt(),
                deviceHash, facts);
    }

    private RequestRiskFacts trustedRiskFacts(TemporaryCheckinRequestFacts requestFacts) {
        if (requestFacts == null || proxyMarker == null
                || !constantEquals(proxyMarker, bytes(requestFacts.proxyMarker()))) {
            throw TemporaryCheckinException.forbiddenIdentity("请求未经受信任代理");
        }
        byte[] ip = parseIp(requestFacts.clientIp());
        byte[] network = ip.length == 4 ? Arrays.copyOf(ip, 3) : Arrays.copyOf(ip, 8);
        String userAgent = cleanUserAgent(requestFacts.userAgent());
        return new RequestRiskFacts(
                hmacHex(riskKey, "ip", ip),
                hmacHex(riskKey, "network", network),
                maskedIp(ip),
                null,
                userAgent == null ? null : hmacHex(riskKey, "user-agent", bytes(userAgent)),
                userAgent);
    }

    private RequestRiskFacts optionalRiskFacts(TemporaryCheckinRequestFacts requestFacts) {
        if (riskKey == null || requestFacts == null || requestFacts.clientIp() == null) return null;
        try {
            byte[] ip = parseIp(requestFacts.clientIp());
            byte[] network = ip.length == 4 ? Arrays.copyOf(ip, 3) : Arrays.copyOf(ip, 8);
            String userAgent = cleanUserAgent(requestFacts.userAgent());
            return new RequestRiskFacts(hmacHex(riskKey, "ip", ip),
                    hmacHex(riskKey, "network", network), maskedIp(ip), null,
                    userAgent == null ? null : hmacHex(riskKey, "user-agent", bytes(userAgent)), userAgent);
        } catch (TemporaryCheckinException ignored) {
            return null;
        }
    }

    private String legacyDeviceHash(TemporaryCheckinRequestFacts requestFacts) {
        if (riskKey == null || requestFacts == null) return null;
        DeviceToken token = parseDevice(requestFacts.deviceCookie());
        return token == null ? null : deviceHash(token.nonce());
    }

    private SalespersonRow activeSalesperson(UUID id) {
        return repository.findSalesperson(tenantId, id)
                .orElseThrow(() -> TemporaryCheckinException.notFound("销售不存在或已停用"));
    }

    private boolean verifyCredential(String rawCode, String encoded) {
        String[] parts = encoded == null ? new String[0] : encoded.split("\\$", -1);
        if (parts.length != 4 || !KDF_PREFIX.equals(parts[0])) return false;
        try {
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 100_000 || iterations > 2_000_000) return false;
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            if (salt.length < 12 || expected.length != HASH_BYTES) return false;
            byte[] actual = pbkdf2(rawCode.toCharArray(), salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] code, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(code, salt, iterations, HASH_BYTES * Byte.SIZE);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256不可用", exception);
        } finally {
            spec.clearPassword();
            Arrays.fill(code, '\0');
        }
    }

    private DeviceToken issueDevice() {
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        String encodedNonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        return new DeviceToken(encodedNonce, sign("device|" + COOKIE_VERSION + "|" + encodedNonce));
    }

    private DeviceToken parseDevice(String value) {
        if (signingKey == null || value == null || value.length() > 512) return null;
        String[] parts = value.split("\\.", -1);
        if (parts.length != 3 || !COOKIE_VERSION.equals(parts[0])) return null;
        try {
            byte[] nonce = Base64.getUrlDecoder().decode(parts[1]);
            if (nonce.length != 32) return null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
        String expected = sign("device|" + COOKIE_VERSION + "|" + parts[1]);
        return constantEquals(expected, parts[2]) ? new DeviceToken(parts[1], parts[2]) : null;
    }

    private String issueIdentityToken(
            UUID salespersonId, int credentialVersion, String deviceHash, Instant issuedAt, Instant expiresAt) {
        String unsigned = String.join(".", COOKIE_VERSION, salespersonId.toString(),
                Integer.toString(credentialVersion), deviceHash,
                Long.toString(issuedAt.getEpochSecond()), Long.toString(expiresAt.getEpochSecond()));
        return unsigned + "." + sign("identity|" + unsigned);
    }

    private IdentityToken parseIdentity(String value) {
        if (signingKey == null || value == null || value.length() > 1024) return null;
        String[] parts = value.split("\\.", -1);
        if (parts.length != 7 || !COOKIE_VERSION.equals(parts[0])) return null;
        String unsigned = String.join(".", Arrays.copyOf(parts, 6));
        if (!constantEquals(sign("identity|" + unsigned), parts[6])) return null;
        try {
            UUID salespersonId = UUID.fromString(parts[1]);
            int credentialVersion = Integer.parseInt(parts[2]);
            if (credentialVersion < 1 || !parts[3].matches("[0-9a-f]{64}")) return null;
            return new IdentityToken(salespersonId, credentialVersion, parts[3],
                    Instant.ofEpochSecond(Long.parseLong(parts[4])),
                    Instant.ofEpochSecond(Long.parseLong(parts[5])));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private ResponseCookie deviceCookie(DeviceToken device) {
        return ResponseCookie.from(DEVICE_COOKIE, COOKIE_VERSION + "." + device.nonce() + "." + device.signature())
                .secure(true).httpOnly(true).sameSite("Strict").path("/")
                .maxAge(Duration.ofDays(properties.getDeviceTtlDays())).build();
    }

    private ResponseCookie identityCookie(String value) {
        return ResponseCookie.from(IDENTITY_COOKIE, value)
                .secure(true).httpOnly(true).sameSite("Strict").path("/")
                .maxAge(Duration.ofDays(properties.getIdentityTtlDays())).build();
    }

    private String deviceHash(String nonce) {
        return hmacHex(riskKey, "device", bytes(nonce));
    }

    private String sign(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(signingKey, bytes(value)));
    }

    private static String hmacHex(byte[] key, String domain, byte[] value) {
        byte[] prefix = bytes(domain + "\0");
        byte[] input = ByteBuffer.allocate(prefix.length + value.length).put(prefix).put(value).array();
        return HexFormat.of().formatHex(hmac(key, input));
    }

    private static byte[] hmac(byte[] key, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256不可用", exception);
        }
    }

    private static byte[] parseIp(String value) {
        if (value == null || value.isBlank() || value.length() > 64 || value.indexOf('%') >= 0) {
            throw TemporaryCheckinException.forbiddenIdentity("受信任代理未提供有效客户端 IP");
        }
        String normalized = value.trim();
        if (normalized.contains(".") && !normalized.contains(":")) return parseIpv4(normalized);
        if (!normalized.matches("[0-9A-Fa-f:]+")) {
            throw TemporaryCheckinException.forbiddenIdentity("受信任代理未提供有效客户端 IP");
        }
        try {
            byte[] bytes = InetAddress.getByName(normalized).getAddress();
            if (bytes.length != 16) {
                throw TemporaryCheckinException.forbiddenIdentity("受信任代理未提供有效客户端 IP");
            }
            return bytes;
        } catch (UnknownHostException exception) {
            throw TemporaryCheckinException.forbiddenIdentity("受信任代理未提供有效客户端 IP");
        }
    }

    private static byte[] parseIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            throw TemporaryCheckinException.forbiddenIdentity("受信任代理未提供有效客户端 IP");
        }
        byte[] result = new byte[4];
        try {
            for (int index = 0; index < parts.length; index++) {
                if (parts[index].isEmpty() || parts[index].length() > 3) throw new NumberFormatException();
                int octet = Integer.parseInt(parts[index]);
                if (octet < 0 || octet > 255) throw new NumberFormatException();
                result[index] = (byte) octet;
            }
            return result;
        } catch (NumberFormatException exception) {
            throw TemporaryCheckinException.forbiddenIdentity("受信任代理未提供有效客户端 IP");
        }
    }

    private static String maskedIp(byte[] ip) {
        if (ip.length == 4) {
            return Byte.toUnsignedInt(ip[0]) + "." + Byte.toUnsignedInt(ip[1]) + "."
                    + Byte.toUnsignedInt(ip[2]) + ".*";
        }
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < 8; index += 2) {
            if (!result.isEmpty()) result.append(':');
            result.append(Integer.toHexString((Byte.toUnsignedInt(ip[index]) << 8)
                    | Byte.toUnsignedInt(ip[index + 1])));
        }
        return result.append("::/64").toString();
    }

    private static String cleanUserAgent(String value) {
        if (value == null || value.isBlank()) return null;
        String cleaned = value.chars()
                .filter(character -> !Character.isISOControl(character))
                .limit(192)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString().trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static byte[] configuredKey(String value, String name, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) throw new IllegalStateException("rigour.sales.temporary-checkin." + name + "未配置");
            return null;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value.trim());
            if (decoded.length < 32) throw new IllegalArgumentException();
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("rigour.sales.temporary-checkin." + name
                    + "必须是至少32字节Base64", exception);
        }
    }

    private static byte[] configuredMarker(String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) throw new IllegalStateException(
                    "rigour.sales.temporary-checkin.trusted-proxy-marker未配置");
            return null;
        }
        byte[] result = bytes(value.trim());
        if (result.length < 32) {
            throw new IllegalStateException(
                    "rigour.sales.temporary-checkin.trusted-proxy-marker至少需32字节");
        }
        return result;
    }

    private void requireConfiguredIdentity() {
        if (signingKey == null || riskKey == null || proxyMarker == null) {
            throw TemporaryCheckinException.conflict("销售身份验证尚未完成服务器配置");
        }
    }

    private static void validateNumbers(TemporaryCheckinProperties properties) {
        if (properties.getCredentialPbkdf2Iterations() < 100_000
                || properties.getCredentialPbkdf2Iterations() > 2_000_000) {
            throw new IllegalStateException("credential-pbkdf2-iterations必须在100000到2000000之间");
        }
        if (properties.getIdentityTtlDays() < 1 || properties.getIdentityTtlDays() > 90
                || properties.getDeviceTtlDays() < 30 || properties.getDeviceTtlDays() > 730) {
            throw new IllegalStateException("销售身份/设备Cookie有效期配置无效");
        }
        if (properties.getRiskIpNetworksPerDay() < 2 || properties.getRiskDevicesPerDay() < 2) {
            throw new IllegalStateException("销售身份风险阈值不能小于2");
        }
    }

    private static String normalizedCode(String value) {
        if (value == null) throw TemporaryCheckinException.badRequest("请输入个人打卡码");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() < 8 || normalized.length() > 128
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw TemporaryCheckinException.badRequest("个人打卡码格式不正确");
        }
        return normalized;
    }

    private String randomTemporaryCode() {
        StringBuilder code = new StringBuilder(10);
        for (int index = 0; index < 10; index++) {
            code.append(TEMPORARY_CODE_ALPHABET[random.nextInt(TEMPORARY_CODE_ALPHABET.length)]);
        }
        return code.toString();
    }

    private static String limited(String value, int maximum, String message) {
        if (value == null || value.isBlank()) throw TemporaryCheckinException.badRequest(message);
        String normalized = value.trim();
        if (normalized.length() > maximum || normalized.chars().anyMatch(Character::isISOControl)) {
            throw TemporaryCheckinException.badRequest(message);
        }
        return normalized;
    }

    private static String normalizedRequired(String value, String message) {
        return limited(value, 64, message);
    }

    private static SalesIdentityView identityView(SalespersonRow row, Instant expiresAt, boolean enabled) {
        return new SalesIdentityView(true, row.id(), row.name(), row.city(), expiresAt, enabled);
    }

    private static String maxRisk(String first, String second) {
        return riskRank(first) >= riskRank(second) ? first : second;
    }

    private static int riskRank(String value) {
        return switch (value) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    private static boolean constantEquals(String first, String second) {
        return constantEquals(bytes(first), bytes(second));
    }

    private static boolean constantEquals(byte[] first, byte[] second) {
        if (first == null || second == null) return false;
        return MessageDigest.isEqual(first, second);
    }

    private static byte[] bytes(String value) {
        return value == null ? null : value.getBytes(StandardCharsets.UTF_8);
    }

    record IdentityVerification(
            SalesIdentityView view,
            ResponseCookie deviceCookie,
            ResponseCookie identityCookie,
            AuthorizedRequest authorized) { }

    record AuthorizedRequest(
            SalespersonRow salesperson,
            String identityMethod,
            Instant verifiedAt,
            Instant expiresAt,
            String deviceTokenHash,
            RequestRiskFacts requestFacts) {
        SalesIdentityView view() {
            return identityView(salesperson, expiresAt, "PERSONAL_CODE".equals(identityMethod));
        }
    }

    record RequestRiskFacts(
            String ipHash,
            String ipNetworkHash,
            String ipMasked,
            String deviceTokenHash,
            String userAgentHash,
            String userAgentSummary) {
        RequestRiskFacts withDevice(String value) {
            return new RequestRiskFacts(ipHash, ipNetworkHash, ipMasked, value,
                    userAgentHash, userAgentSummary);
        }
    }

    record RiskSnapshot(
            String level,
            List<String> flags,
            RequestRiskFacts requestFacts,
            Instant evaluatedAt) { }

    record IssuedTemporaryCode(UUID salespersonId, String temporaryCode, int credentialVersion, Instant issuedAt) { }

    private record DeviceToken(String nonce, String signature) { }
    private record IdentityToken(
            UUID salespersonId, int credentialVersion, String deviceHash, Instant issuedAt, Instant expiresAt) { }
}
