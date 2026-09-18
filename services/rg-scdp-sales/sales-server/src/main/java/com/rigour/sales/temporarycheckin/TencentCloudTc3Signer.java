package com.rigour.sales.temporarycheckin;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 腾讯云 API 3.0 TC3-HMAC-SHA256 最小签名器。 */
final class TencentCloudTc3Signer {

    static final String ALGORITHM = "TC3-HMAC-SHA256";
    static final String CONTENT_TYPE = "application/json; charset=utf-8";
    private static final String SIGNED_HEADERS = "content-type;host";
    private static final String TERMINATOR = "tc3_request";
    private static final DateTimeFormatter UTC_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private TencentCloudTc3Signer() {
    }

    static SignedHeaders sign(
            String host,
            String service,
            String secretId,
            String secretKey,
            String payload,
            Instant now) {
        String timestamp = Long.toString(now.getEpochSecond());
        String date = UTC_DATE.format(now);
        String canonicalHeaders = "content-type:" + CONTENT_TYPE + "\n"
                + "host:" + host + "\n";
        String hashedPayload = sha256Hex(payload);
        String canonicalRequest = "POST\n/\n\n" + canonicalHeaders + "\n"
                + SIGNED_HEADERS + "\n" + hashedPayload;
        String credentialScope = date + "/" + service + "/" + TERMINATOR;
        String stringToSign = ALGORITHM + "\n" + timestamp + "\n" + credentialScope + "\n"
                + sha256Hex(canonicalRequest);
        byte[] secretDate = hmac(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] secretService = hmac(secretDate, service);
        byte[] secretSigning = hmac(secretService, TERMINATOR);
        String signature = HexFormat.of().formatHex(hmac(secretSigning, stringToSign));
        String authorization = ALGORITHM + " Credential=" + secretId + "/" + credentialScope
                + ", SignedHeaders=" + SIGNED_HEADERS + ", Signature=" + signature;
        return new SignedHeaders(authorization, timestamp);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("TC3_SHA256_UNAVAILABLE", exception);
        }
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("TC3_HMAC_UNAVAILABLE", exception);
        }
    }

    /** 签名生成的请求头值。 */
    record SignedHeaders(String authorization, String timestamp) {
    }
}
