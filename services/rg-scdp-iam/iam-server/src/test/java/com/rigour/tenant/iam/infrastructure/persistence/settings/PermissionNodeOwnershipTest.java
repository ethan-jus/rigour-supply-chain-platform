package com.rigour.tenant.iam.infrastructure.persistence.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.rigour.tenant.iam.domain.model.settings.AppMenuTree.Node;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PermissionNodeOwnershipTest {
    private static Node node(String kind, String code, String status) {
        UUID id = UUID.randomUUID();
        return new Node(id, null, kind, id, kind, null, 0, true, status, false, 0,
                null, code, null, null, null);
    }

    @Test void aSharedPageCannotGrantItsOwnAction() {
        var page = node("PAGE", "analytics:dashboard:read", "ACTIVE");
        var other = node("PAGE", "hr:department:read", "ACTIVE");
        var button = node("BUTTON", "analytics:dashboard:read", "ACTIVE");
        var catalog = List.of(page, other, button);
        assertThat(JdbcAppSettingsStore.enabledPermissionNodes(catalog, catalog))
                .containsExactly(other, button);
        assertThat(JdbcAppSettingsStore.enabledPermissionNodes(List.of(page, other), catalog))
                .containsExactly(other);
        var disabled = new Node(button.id(), null, "BUTTON", button.resourceId(), button.name(), null,
                0, false, "DISABLED", false, 0, null, button.permissionCode(), null, null, null);
        assertThat(JdbcAppSettingsStore.enabledPermissionNodes(List.of(page, other, disabled), catalog))
                .containsExactly(other);
    }
}
