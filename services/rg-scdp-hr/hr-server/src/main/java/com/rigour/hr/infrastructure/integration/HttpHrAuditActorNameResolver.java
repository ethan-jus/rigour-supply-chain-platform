package com.rigour.hr.infrastructure.integration;

import com.rigour.hr.application.port.out.HrAuditActorNameResolver;
import com.rigour.tenant.iam.api.v1.model.CurrentUserView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/** 用原登录凭据向 IAM 读取操作人；不接受表单传入的审计姓名，也不查询 IAM 数据表。 */
@Component
public final class HttpHrAuditActorNameResolver implements HrAuditActorNameResolver {
    private final RestClient client;

    public HttpHrAuditActorNameResolver(
            @Value("${rigour.supply-authorization.iam-base-url:${IAM_BASE_URL:http://rigour-tenant-iam-service:26881}}") String base) {
        URI uri = URI.create(base);
        if (!java.util.Set.of("http", "https").contains(uri.getScheme()) || uri.getUserInfo() != null)
            throw new IllegalArgumentException("IAM 地址无效");
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        client = RestClient.builder().baseUrl(base).requestFactory(factory).build();
    }

    @Override
    public String resolve(String tenantId, String actorId) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return null;
        String token = attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
        if (token == null || !token.startsWith("Bearer ")) return null;
        try {
            CurrentUserView user = client.get().uri("/api/v1/me")
                    .header(HttpHeaders.AUTHORIZATION, token).retrieve().body(CurrentUserView.class);
            if (user == null || !"TENANT".equals(user.principalScope())
                    || !actorId.equals(String.valueOf(user.id()))
                    || !tenantId.equals(String.valueOf(user.tenantId()))) return null;
            String name = user.displayName() == null || user.displayName().isBlank() ? user.username() : user.displayName();
            return name != null && name.length() <= 128 ? name : null;
        } catch (org.springframework.web.client.RestClientException ex) {
            // 名称服务失败不影响已验证的操作人 ID；不得把错误响应或凭据写入日志。
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("部门审计显示名称未解析，保留操作人账号 ID，原因={}", ex.getClass().getSimpleName());
            return null;
        }
    }
}
