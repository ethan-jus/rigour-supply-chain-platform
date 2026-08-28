package com.rigour.sales.temporarycheckin;

import com.rigour.sales.temporarycheckin.TemporaryCheckinGovernanceModels.SalespersonBootstrapResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 首次启用身份校验时为尚无个人码的在职销售签发一次性代码；接口默认关闭。 */
@RestController
@ConditionalOnProperty(prefix = "rigour.sales.temporary-checkin", name = "enabled", havingValue = "true")
final class TemporaryCheckinSalespersonBootstrapController {

    private final TemporaryCheckinSalespersonAdminService service;
    private final TemporaryCheckinAdminAuthProperties properties;

    TemporaryCheckinSalespersonBootstrapController(
            TemporaryCheckinSalespersonAdminService service,
            TemporaryCheckinAdminAuthProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping("/sales-checkin/internal/v1/salesperson-credentials-bootstrap")
    ResponseEntity<SalespersonBootstrapResponse> bootstrap(
            @RequestHeader(name = TemporaryCheckinAdminBootstrapController.BOOTSTRAP_SECRET_HEADER,
                    required = false) String secret,
            @RequestHeader(name = TemporaryCheckinRequestFacts.PROXY_MARKER_HEADER,
                    required = false) String marker,
            HttpServletRequest request) {
        if (blank(properties.getBootstrapSecret()) || blank(properties.getBootstrapProxyMarker())) {
            throw TemporaryCheckinException.notFound("接口不存在");
        }
        if (!TemporaryCheckinAdminBootstrapController.isAllowedRemote(
                    request.getRemoteAddr(), properties.getBootstrapAllowedRemoteCidrs())
                || !same(properties.getBootstrapSecret(), secret)
                || !same(properties.getBootstrapProxyMarker(), marker)) {
            throw TemporaryCheckinException.adminForbidden("引导请求未通过内部代理校验");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.bootstrapUnconfiguredCredentials());
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
