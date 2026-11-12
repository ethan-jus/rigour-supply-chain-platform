package com.rigour.integration.domain.thirdparty.oauth;

import java.net.URI;
import java.util.*;

/** 本地HTTP例外仅由部署运行环境启用，不信任浏览器提交的环境选项。 */
public final class OAuthCallbackUris {
    private OAuthCallbackUris() {}

    public static URI require(String value, String path, boolean allowPrivateHttp) {
        URI uri;
        try {
            uri = URI.create(value == null ? "" : value);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        String host = Objects.toString(uri.getHost(), "").toLowerCase(Locale.ROOT);
        boolean local = Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(host);
        boolean scheme =
                "https".equals(uri.getScheme())
                        || "http".equals(uri.getScheme())
                                && allowPrivateHttp
                                && (local || privateIpv4(host));
        if (host.isEmpty()
                || !scheme
                || !path.equals(uri.getPath())
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || uri.getPort() == 0
                || uri.getPort() > 65535) throw invalid();
        return uri;
    }

    private static boolean privateIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] p = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("0|[1-9][0-9]{0,2}")) return false;
            p[i] = Integer.parseInt(parts[i]);
            if (p[i] > 255) return false;
        }
        return p[0] == 10 || p[0] == 172 && p[1] >= 16 && p[1] <= 31 || p[0] == 192 && p[1] == 168;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("授权回调须使用指定后端HTTPS接口；仅本地和DEV允许本机私网IP使用HTTP");
    }
}
