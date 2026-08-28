package com.rigour.sales.temporarycheckin;

import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAuthModels.BootstrapRequest;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAuthModels.BootstrapResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 首次切换应用认证时，从受信内部代理一次性导入城市和账号；接口默认关闭。 */
@RestController
@ConditionalOnProperty(prefix = "rigour.sales.temporary-checkin", name = "enabled", havingValue = "true")
final class TemporaryCheckinAdminBootstrapController {

    static final String BOOTSTRAP_SECRET_HEADER = "X-Sales-Checkin-Bootstrap-Secret";

    private final TemporaryCheckinAdminAuthService authService;
    private final TemporaryCheckinAdminAuthProperties properties;

    TemporaryCheckinAdminBootstrapController(
            TemporaryCheckinAdminAuthService authService,
            TemporaryCheckinAdminAuthProperties properties) {
        this.authService = authService;
        this.properties = properties;
    }

    @PostMapping("/sales-checkin/internal/v1/admin-bootstrap")
    ResponseEntity<BootstrapResponse> bootstrap(
            @RequestHeader(name = BOOTSTRAP_SECRET_HEADER, required = false) String secret,
            @RequestHeader(name = TemporaryCheckinRequestFacts.PROXY_MARKER_HEADER, required = false) String marker,
            @RequestBody BootstrapRequest body,
            HttpServletRequest request) {
        if (blank(properties.getBootstrapSecret()) || blank(properties.getBootstrapProxyMarker())) {
            throw TemporaryCheckinException.notFound("接口不存在");
        }
        if (!isAllowedRemote(request.getRemoteAddr(), properties.getBootstrapAllowedRemoteCidrs())
                || !same(properties.getBootstrapSecret(), secret)
                || !same(properties.getBootstrapProxyMarker(), marker)) {
            throw TemporaryCheckinException.adminForbidden("引导请求未通过内部代理校验");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(authService.bootstrap(body));
    }

    static boolean isAllowedRemote(String address, java.util.List<String> allowedCidrs) {
        if (address == null || allowedCidrs == null || allowedCidrs.isEmpty()) return false;
        try {
            byte[] remote = InetAddress.getByName(address).getAddress();
            for (String rawCidr : allowedCidrs) {
                if (matches(remote, rawCidr)) return true;
            }
            return false;
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    private static boolean matches(byte[] remote, String rawCidr) {
        if (rawCidr == null || rawCidr.isBlank()) return false;
        String[] parts = rawCidr.trim().split("/", -1);
        if (parts.length != 2) return false;
        try {
            byte[] network = InetAddress.getByName(parts[0]).getAddress();
            int prefix = Integer.parseInt(parts[1]);
            int minimumPrefix = remote.length == 4 ? 24 : 120;
            if (network.length != remote.length || prefix < minimumPrefix || prefix > remote.length * 8) {
                return false;
            }
            int wholeBytes = prefix / 8;
            int remainingBits = prefix % 8;
            for (int index = 0; index < wholeBytes; index++) {
                if (remote[index] != network[index]) return false;
            }
            if (remainingBits == 0) return true;
            int mask = 0xff << (8 - remainingBits);
            return (remote[wholeBytes] & mask) == (network[wholeBytes] & mask);
        } catch (UnknownHostException | NumberFormatException exception) {
            return false;
        }
    }

    private static boolean same(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
