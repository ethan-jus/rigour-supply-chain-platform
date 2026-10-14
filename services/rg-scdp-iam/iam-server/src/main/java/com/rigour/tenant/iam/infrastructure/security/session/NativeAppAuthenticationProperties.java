package com.rigour.tenant.iam.infrastructure.security.session;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rigour.iam.authentication.native-app")
public final class NativeAppAuthenticationProperties {
    private boolean enabled;
    private Set<String> allowedClientIds = Set.of();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Set<String> getAllowedClientIds() { return allowedClientIds; }
    public void setAllowedClientIds(Set<String> ids) { allowedClientIds = ids == null ? Set.of() : Set.copyOf(ids); }

    public void validate() {
        if (allowedClientIds.isEmpty() || allowedClientIds.stream()
                .anyMatch(id -> !id.matches("rigour-sales-app-(android|ios|harmony)(-dev)?"))) {
            throw new IllegalStateException("Native login requires explicit dedicated Sales App client ids");
        }
    }
}
