package com.rigour.tenant.iam.infrastructure.bootstrap;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** HTTP 开发开关不能改变默认策略或允许带用户信息的回调。 */
class PortalClientBootstrapPropertiesTest {
    @Test
    void requiresExplicitDesktopHttpOptIn() {
        var properties = new PortalClientBootstrapProperties();
        properties.setRedirectUri("http://192.168.12.7:5100/oidc/callback");
        properties.setPostLogoutRedirectUri("http://192.168.12.7:5100/");
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
        properties.setAllowInsecureHttp(true);
        assertThatCode(properties::validate).doesNotThrowAnyException();
        properties.setRedirectUri("http://user:pass@192.168.12.7:5100/oidc/callback");
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }
}
