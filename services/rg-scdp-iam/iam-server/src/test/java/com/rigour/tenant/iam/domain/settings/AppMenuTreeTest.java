package com.rigour.tenant.iam.domain.settings;

import static org.assertj.core.api.Assertions.*;

import com.rigour.tenant.iam.domain.model.settings.AppMenuTree;
import com.rigour.tenant.iam.domain.model.settings.AppMenuTree.Node;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 验证可配置树的业务不变量和隐藏/停用区别。 */
class AppMenuTreeTest {
    private Node node(
            UUID id,
            UUID parent,
            String type,
            boolean visible,
            String status,
            boolean protectedNode) {
        return new Node(
                id,
                parent,
                type,
                "MENU".equals(type) ? null : id,
                "菜单",
                "Folder",
                10,
                visible,
                status,
                protectedNode,
                0,
                null,
                null,
                null,
                null,
                null);
    }

    private Node customPage(UUID id, String routeKey, String routePath, String componentPath) {
        return new Node(
                id,
                null,
                "PAGE",
                null,
                "自定义页面",
                null,
                10,
                true,
                "ACTIVE",
                false,
                0,
                "CUSTOM." + id,
                "order:read",
                routeKey,
                routePath,
                componentPath);
    }

    @Test
    void requiresSupportedIconAndNormalizesLegacyCatalogOnly() {
        UUID id = UUID.randomUUID();
        Node invalid =
                new Node(
                        id,
                        null,
                        "MENU",
                        null,
                        "目录",
                        "UnknownIcon",
                        0,
                        true,
                        "ACTIVE",
                        false,
                        0,
                        null,
                        null,
                        null,
                        null,
                        null);
        assertThatThrownBy(() -> AppMenuTree.validate(List.of(invalid)))
                .hasMessageContaining("内置图标");
        assertThat(
                        com.rigour.tenant.iam.domain.model.settings.AppMenuIcons.legacy(
                                "inventory-warehouse"))
                .isEqualTo("Box");
        assertThat(com.rigour.tenant.iam.domain.model.settings.AppMenuIcons.legacy("House"))
                .isEqualTo("House");
    }

    @Test
    void rejectsMixedCycles() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertThatThrownBy(
                        () ->
                                AppMenuTree.validate(
                                        List.of(
                                                node(a, b, "MENU", true, "ACTIVE", false),
                                                node(b, a, "MENU", true, "ACTIVE", false))))
                .hasMessageContaining("子菜单");
    }

    @Test
    void buttonsRequireTheirPageAndPagesRequireDirectories() {
        UUID folder = UUID.randomUUID(), page = UUID.randomUUID(), button = UUID.randomUUID();
        assertThatThrownBy(
                        () ->
                                AppMenuTree.validate(
                                        List.of(
                                                node(folder, null, "MENU", true, "ACTIVE", false),
                                                node(
                                                        button, folder, "BUTTON", true, "ACTIVE",
                                                        false))))
                .hasMessageContaining("页面下");
        assertThatCode(
                        () ->
                                AppMenuTree.validate(
                                        List.of(
                                                node(folder, null, "MENU", true, "ACTIVE", false),
                                                node(page, folder, "PAGE", true, "ACTIVE", false),
                                                node(
                                                        button, page, "BUTTON", false, "ACTIVE",
                                                        false))))
                .doesNotThrowAnyException();
    }

    @Test
    void preventsMovingProtectedEntryUnderHiddenAncestor() {
        UUID folder = UUID.randomUUID(), page = UUID.randomUUID();
        assertThatThrownBy(
                        () ->
                                AppMenuTree.validate(
                                        List.of(
                                                node(folder, null, "MENU", false, "ACTIVE", false),
                                                node(page, folder, "PAGE", true, "ACTIVE", true))))
                .hasMessageContaining("恢复入口");
    }

    @Test
    void hiddenIsNotDisabledButDisabledAncestorBlocksExecution() {
        UUID folder = UUID.randomUUID(), page = UUID.randomUUID();
        Node child = node(page, folder, "PAGE", false, "ACTIVE", false);
        assertThat(
                        AppMenuTree.enabled(
                                child,
                                Map.of(
                                        folder,
                                        node(folder, null, "MENU", false, "ACTIVE", false),
                                        page,
                                        child)))
                .isTrue();
        assertThat(
                        AppMenuTree.enabled(
                                child,
                                Map.of(
                                        folder,
                                        node(folder, null, "MENU", true, "DISABLED", false),
                                        page,
                                        child)))
                .isFalse();
    }

    @Test
    void rejectsParentOutsideCurrentTenantSnapshot() {
        assertThatThrownBy(
                        () ->
                                AppMenuTree.validate(
                                        List.of(
                                                node(
                                                        UUID.randomUUID(),
                                                        UUID.randomUUID(),
                                                        "PAGE",
                                                        true,
                                                        "ACTIVE",
                                                        false))))
                .hasMessageContaining("当前应用");
    }

    @Test
    void customPagesRequireRouteKeyRoutePathAndComponentPath() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(
                        () ->
                                AppMenuTree.validate(
                                        List.of(
                                                customPage(
                                                        id,
                                                        null,
                                                        "/supply-chain/custom/audit",
                                                        "supply-chain/custom/AuditView.vue"))))
                .hasMessageContaining("自定义页面必须填写路由地址和组件路径");
        assertThatThrownBy(
                        () ->
                                AppMenuTree.validate(
                                        List.of(
                                                customPage(
                                                        id,
                                                        "tenant.custom.page.audit",
                                                        "/supply-chain/custom/audit",
                                                        null))))
                .hasMessageContaining("自定义页面必须填写路由地址和组件路径");
        assertThatThrownBy(
                        () ->
                                AppMenuTree.validate(
                                        List.of(
                                                customPage(
                                                        id,
                                                        "tenant.custom.page.audit",
                                                        " ",
                                                        "supply-chain/custom/AuditView.vue"))))
                .hasMessageContaining("自定义页面必须填写路由地址和组件路径");
        assertThatCode(
                        () ->
                                AppMenuTree.validate(
                                        List.of(
                                                customPage(
                                                        id,
                                                        "tenant.custom.page.audit",
                                                        "/supply-chain/custom/audit",
                                                        "supply-chain/custom/AuditView.vue"))))
                .doesNotThrowAnyException();
    }
}
