package com.rigour.integration.application.service.feishu;

import com.rigour.integration.application.port.out.FeishuJsapiClient;
import com.rigour.integration.application.port.out.FeishuJsapiClientException;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.shared.context.RequestContext;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 飞书 JSSDK 签名应用服务；只签署已配置来源的完整页面 URL。 */
public final class FeishuJsapiSignService {

    private static final Logger log = LoggerFactory.getLogger(FeishuJsapiSignService.class);
    private final FeishuJsapiClient client;
    private final String appId;
    private final List<String> configuredOrigins;
    private final boolean allowInsecureLan;
    private final Clock clock;
    private final SecureRandom secureRandom;

    public FeishuJsapiSignService(FeishuJsapiClient client, String appId, List<String> allowedOrigins) {
        this(client, appId, allowedOrigins, false, Clock.systemUTC(), new SecureRandom());
    }

    public FeishuJsapiSignService(FeishuJsapiClient client, String appId, List<String> allowedOrigins,
                                  boolean allowInsecureLan) {
        this(client, appId, allowedOrigins, allowInsecureLan, Clock.systemUTC(), new SecureRandom());
    }

    FeishuJsapiSignService(FeishuJsapiClient client, String appId, List<String> allowedOrigins,
                           Clock clock, SecureRandom secureRandom) {
        this(client, appId, allowedOrigins, false, clock, secureRandom);
    }

    FeishuJsapiSignService(FeishuJsapiClient client, String appId, List<String> allowedOrigins,
                           boolean allowInsecureLan, Clock clock, SecureRandom secureRandom) {
        this.client = Objects.requireNonNull(client, "client cannot be null");
        this.appId = appId;
        this.configuredOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        this.allowInsecureLan = allowInsecureLan;
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom cannot be null");
        log.info("飞书 JSSDK 签名配置加载 appId={} allowedOrigins={}", appId, this.configuredOrigins);
    }

    public SignResult sign(String url) {
        long startedAt = System.nanoTime();
        validateConfiguration();
        URI page = parsePageUrl(url);
        String pageOrigin = origin(page);
        if (!allowedOrigins().contains(pageOrigin) && !(allowInsecureLan && isPrivateLanHttp(page))) {
            log.warn("飞书 JSSDK 来源未配置 requestId={} origin={} elapsedMs={}",
                    RequestContext.getRequestId(), pageOrigin, elapsedMillis(startedAt));
            throw new BusinessException(ErrorCode.FEISHU_JSAPI_ORIGIN_NOT_ALLOWED);
        }

        // 飞书官方 JSSDK 示例（larksuite/lark-samples react_and_nodejs/web_app）使用
        // Date.now() 即毫秒级时间戳；秒级值会被飞书校验服务按毫秒解析成 1970 年，
        // 表现为 jsapi-config errno=2601002 "signature is expired" (333444)。
        // 签名串中的 timestamp 与下发给 h5sdk.config 的值必须逐字符一致。
        String timestamp = Long.toString(clock.instant().toEpochMilli());
        String nonceStr = nonce();
        String ticket;
        try {
            ticket = client.getJsapiTicket();
        } catch (FeishuJsapiClientException exception) {
            log.warn("飞书 JSSDK 票据获取失败 requestId={} code={} httpStatus={} elapsedMs={}",
                    RequestContext.getRequestId(), exception.code(), exception.httpStatus(),
                    elapsedMillis(startedAt));
            ErrorCode errorCode = "FEISHU_CONFIG_INVALID".equals(exception.code())
                    ? ErrorCode.FEISHU_JSAPI_CONFIG_INVALID
                    : ErrorCode.FEISHU_JSAPI_UPSTREAM_FAILED;
            throw new BusinessException(errorCode);
        }
        String signature = sha1("jsapi_ticket=" + ticket
                + "&noncestr=" + nonceStr
                + "&timestamp=" + timestamp
                + "&url=" + url);
        log.info("飞书 JSSDK 签名成功 requestId={} origin={} pageUrl={} appId={} elapsedMs={}",
                RequestContext.getRequestId(), pageOrigin, url, appId, elapsedMillis(startedAt));
        return new SignResult(appId, timestamp, nonceStr, signature, url);
    }

    private static long elapsedMillis(long startedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private void validateConfiguration() {
        try {
            if (appId == null || appId.isBlank() || configuredOrigins.isEmpty()) {
                throw new IllegalStateException("飞书 JSSDK 签名配置未完成");
            }
            allowedOrigins();
        } catch (IllegalArgumentException | IllegalStateException exception) {
            log.warn("飞书 JSSDK 签名配置无效 code=FEISHU_JSAPI_CONFIG_INVALID");
            throw new BusinessException(ErrorCode.FEISHU_JSAPI_CONFIG_INVALID);
        }
    }

    private List<String> allowedOrigins() {
        return configuredOrigins.stream().map(FeishuJsapiSignService::normalizeOrigin).toList();
    }

    private static URI parsePageUrl(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new BusinessException(ErrorCode.FEISHU_JSAPI_URL_INVALID);
        }
        final URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.FEISHU_JSAPI_URL_INVALID);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || uri.getHost() == null || uri.getHost().isBlank()
                || uri.getUserInfo() != null
                || uri.getRawFragment() != null) {
            throw new BusinessException(ErrorCode.FEISHU_JSAPI_URL_INVALID);
        }
        return uri;
    }

    private static String normalizeOrigin(String value) {
        final URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid allowed origin", exception);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || uri.getHost() == null || uri.getHost().isBlank()
                || uri.getUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                && !"/".equals(uri.getRawPath()))) {
            throw new IllegalArgumentException("allowed origin must contain scheme and host only");
        }
        return origin(uri);
    }

    private static String origin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443);
        return scheme + "://" + host + (port < 0 || defaultPort ? "" : ":" + port);
    }

    private static boolean isPrivateLanHttp(URI uri) {
        if (!"http".equalsIgnoreCase(uri.getScheme())) return false;
        String host = uri.getHost();
        if (host == null) return false;
        String[] segments = host.split("\\.", -1);
        if (segments.length != 4) return false;
        int[] octets = new int[4];
        try {
            for (int index = 0; index < segments.length; index++) {
                if (segments[index].isEmpty()) return false;
                octets[index] = Integer.parseInt(segments[index]);
                if (octets[index] < 0 || octets[index] > 255) return false;
            }
        } catch (NumberFormatException exception) {
            return false;
        }
        return octets[0] == 10
                || octets[0] == 192 && octets[1] == 168
                || octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31;
    }

    private String nonce() {
        // 官方示例 nonceStr 为纯字母数字；避免 base64url 的 -_ 字符引入客户端字符集变量。
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder builder = new StringBuilder(22);
        for (int i = 0; i < 22; i++) {
            builder.append(alphabet.charAt(secureRandom.nextInt(alphabet.length())));
        }
        return builder.toString();
    }

    private static String sha1(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 不支持 SHA-1", exception);
        }
    }

    public record SignResult(String appId, String timestamp, String nonceStr, String signature,
                             String signedUrl) { }
}
