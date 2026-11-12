package com.rigour.integration.infrastructure.thirdparty.huolala.oauth;

import com.rigour.integration.api.v1.model.HuolalaModels.Connection;
import com.rigour.integration.application.port.out.thirdparty.huolala.*;
import com.rigour.integration.application.port.out.thirdparty.oauth.ThirdPartyOAuthProvider;
import com.rigour.integration.application.service.thirdparty.huolala.HuolalaService.DeploymentSettings;
import com.rigour.integration.domain.thirdparty.oauth.*;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

@Component
public final class HuolalaOAuthProvider implements ThirdPartyOAuthProvider {
    private final HuolalaStore store;
    private final HuolalaGateway gateway;
    private final DeploymentSettings deployment;

    public HuolalaOAuthProvider(
            HuolalaStore store, HuolalaGateway gateway, DeploymentSettings deployment) {
        this.store = store;
        this.gateway = gateway;
        this.deployment = deployment;
    }

    @Override
    public String platformCode() {
        return "HUOLALA";
    }

    @Override
    public String permissionPrefix() {
        return "integration:huolala";
    }

    private Connection connection(UUID tenant, UUID connector) {
        var c =
                store.connection(tenant)
                        .orElseThrow(() -> new IllegalStateException("请先保存货拉拉同步设置"));
        if (!c.id().equals(connector.toString())) throw new IllegalArgumentException("平台连接不存在");
        if (!gateway.ready(c)) throw new IllegalStateException("货拉拉应用凭据未配置完整");
        // 回调和刷新不得使用已被新的部署配置替换的旧绑定。
        if (configured(deployment.enterpriseId())
                        && !Objects.equals(deployment.enterpriseId().trim(), c.enterpriseId())
                || configured(deployment.redirectUri())
                        && !Objects.equals(deployment.redirectUri().trim(), c.redirectUri())
                || deployment.sandbox() != null && deployment.sandbox() != c.sandbox())
            throw new IllegalStateException("接入配置已变更，请重新保存同步设置并授权");
        return c;
    }

    private static boolean configured(String v) {
        return v != null && !v.isBlank();
    }

    @Override
    public OAuthConfiguration configuration(UUID tenant, UUID connector) {
        var c = connection(tenant, connector);
        var uri =
                OAuthCallbackUris.require(
                        c.redirectUri(),
                        "/api/v1/integration/huolala/oauth/callback",
                        deployment.allowPrivateHttp());
        String appKey = gateway.appKey(c);
        try {
            String fingerprint =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    (appKey
                                                                    + "\n"
                                                                    + c.enterpriseId()
                                                                    + "\n"
                                                                    + c.sandbox()
                                                                    + "\n"
                                                                    + c.secretRef()
                                                                    + "\n"
                                                                    + c.redirectUri())
                                                            .getBytes(StandardCharsets.UTF_8)));
            return new OAuthConfiguration(
                    tenant, platformCode(), connector, appKey, uri, fingerprint);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("授权配置校验失败");
        }
    }

    @Override
    public URI authorizationUri(OAuthConfiguration c) {
        return URI.create(gateway.authorizeUrl(connection(c.tenantId(), c.connectorId())));
    }

    @Override
    public OAuthToken exchange(OAuthConfiguration c, String code) {
        return gateway.exchange(connection(c.tenantId(), c.connectorId()), code);
    }

    @Override
    public OAuthToken refresh(OAuthConfiguration c, OAuthToken previous) {
        return gateway.refresh(connection(c.tenantId(), c.connectorId()), previous);
    }

    @Override
    public boolean verifySubject(OAuthConfiguration configuration, OAuthToken token) {
        var c = connection(configuration.tenantId(), configuration.connectorId());
        var proof = gateway.read(c, token, "e-order-list", Map.of("page_no", 1, "page_size", 1));
        boolean found = false;
        for (var order : proof.path("order_arr")) {
            String enterprise = order.path("ep_id").asText(null);
            if (enterprise == null || enterprise.isBlank()) return false;
            if (!c.enterpriseId().equals(enterprise))
                throw new OAuthFailure(
                        OAuthFailure.Kind.REAUTHORIZE, "SUBJECT_MISMATCH", "授权账号所属企业与配置不一致");
            found = true;
        }
        return found;
    }
}
