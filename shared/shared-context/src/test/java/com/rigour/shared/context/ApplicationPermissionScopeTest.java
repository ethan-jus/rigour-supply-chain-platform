package com.rigour.shared.context;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

/** 应用覆盖不能继承全局通配权限，异常退出必须恢复原上下文。 */
class ApplicationPermissionScopeTest {
    @Test
    void applicationPermissionsReplaceGlobalWildcardAndRestoreOnFailure() {
        UUID user = UUID.randomUUID();
        var caller =
                new CallerIdentity(
                        "TENANT",
                        user,
                        UUID.randomUUID(),
                        user,
                        null,
                        UUID.randomUUID(),
                        0,
                        0,
                        0,
                        Set.of("TENANT_SUPER_ADMIN"),
                        Set.of("*:*:*"));
        AuthorizationContext.set(caller);
        try {
            assertThatThrownBy(
                            () -> {
                                try (var ignored =
                                        AuthorizationContext.useVerifiedApplication(
                                                "SUPPLY_CHAIN", Set.of("order:read"))) {
                                    assertThat(AuthorizationContext.hasPermission("order:read"))
                                            .isTrue();
                                    assertThat(AuthorizationContext.hasPermission("order:write"))
                                            .isFalse();
                                    assertThat(AuthorizationContext.requireCurrent().roles())
                                            .isEmpty();
                                    throw new IllegalStateException("operation failed");
                                }
                            })
                    .hasMessage("operation failed");
            assertThat(AuthorizationContext.requireCurrent()).isSameAs(caller);
            assertThat(AuthorizationContext.hasPermission("order:write")).isTrue();
            assertThatThrownBy(
                            () ->
                                    AuthorizationContext.useVerifiedApplication(
                                            "SUPPLY_CHAIN", Set.of("*:*:*")))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            AuthorizationContext.clear();
        }
        assertThat(AuthorizationContext.current()).isEmpty();
    }
}
