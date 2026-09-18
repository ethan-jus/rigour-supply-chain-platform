package com.rigour.tenant.iam.domain.model.navigation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

/** 系统目录和租户目录混合编辑时仍保持无环、同应用、目录父级约束。 */
class MenuHierarchyTest {
    private final UUID app = UUID.randomUUID();
    private final UUID root = UUID.randomUUID();
    private final UUID group = UUID.randomUUID();
    private final UUID page = UUID.randomUUID();

    private Map<UUID, MenuHierarchy.Node> nodes() {
        return Map.of(
                root,
                new MenuHierarchy.Node(root, app, null, true),
                group,
                new MenuHierarchy.Node(group, app, root, true),
                page,
                new MenuHierarchy.Node(page, app, group, false));
    }

    @Test
    void acceptsPageInsideNestedDirectoryAndMovingDirectoryToRoot() {
        assertThatCode(() -> MenuHierarchy.validateMove(nodes(), app, page, group))
                .doesNotThrowAnyException();
        assertThatCode(() -> MenuHierarchy.validateMove(nodes(), app, group, null))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMixedDirectoryCycleAndSelfParent() {
        assertThatThrownBy(() -> MenuHierarchy.validateMove(nodes(), app, root, group))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MenuHierarchy.validateMove(nodes(), app, group, group))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownCrossApplicationAndPageParents() {
        assertThatThrownBy(() -> MenuHierarchy.validateMove(nodes(), app, UUID.randomUUID(), page))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                MenuHierarchy.validateMove(
                                        nodes(), UUID.randomUUID(), UUID.randomUUID(), root))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MenuHierarchy.validateMove(nodes(), app, page, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
