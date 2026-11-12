package com.rigour.integration.application.service.thirdparty.oauth;

import com.rigour.integration.application.port.out.thirdparty.oauth.ThirdPartyOAuthProvider;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ThirdPartyProviderRegistry {
    private final Map<String, ThirdPartyOAuthProvider> providers;

    public ThirdPartyProviderRegistry(List<ThirdPartyOAuthProvider> source) {
        var map = new HashMap<String, ThirdPartyOAuthProvider>();
        for (var provider : source) {
            if (!provider.platformCode().matches("[A-Z][A-Z0-9_]{0,31}")
                    || map.putIfAbsent(provider.platformCode(), provider) != null)
                throw new IllegalStateException("三方平台适配器编码重复或无效");
        }
        providers = Map.copyOf(map);
    }

    public ThirdPartyOAuthProvider require(String code) {
        var provider = providers.get(code);
        if (provider == null) throw new IllegalArgumentException("该平台尚未配置OAuth接入");
        return provider;
    }
}
