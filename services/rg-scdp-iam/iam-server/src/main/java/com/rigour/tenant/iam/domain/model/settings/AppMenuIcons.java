package com.rigour.tenant.iam.domain.model.settings;

import java.util.*;

/** 内置图标稳定键。只保存可预览的受支持图标，来源旧键在初始化时转换。 */
public final class AppMenuIcons {
    private AppMenuIcons() {}

    public static final Set<String> KEYS =
            Set.of(
                    "House",
                    "Setting",
                    "Menu",
                    "Document",
                    "List",
                    "User",
                    "UserFilled",
                    "Avatar",
                    "Lock",
                    "Key",
                    "OfficeBuilding",
                    "Folder",
                    "FolderOpened",
                    "Shop",
                    "Goods",
                    "Box",
                    "Van",
                    "Location",
                    "TrendCharts",
                    "DataAnalysis",
                    "Calendar",
                    "Wallet",
                    "CreditCard",
                    "Tickets",
                    "Connection",
                    "Tools",
                    "Monitor",
                    "Bell",
                    "Search",
                    "Operation");

    public static String legacy(String key) {
        if (key == null || key.isBlank()) return null;
        if (KEYS.contains(key)) return key;
        String v = key.toLowerCase(Locale.ROOT);
        if (v.contains("home") || v.contains("dashboard") || v.contains("odometer")) return "House";
        if (v.contains("erp") || v.contains("product") || v.contains("goods")) return "Goods";
        if (v.contains("inventory") || v.contains("stock") || v.contains("warehouse")) return "Box";
        if (v.contains("customer") || v.contains("crm") || v.contains("shop")) return "Shop";
        if (v.contains("sales")
                || v.contains("visit")
                || v.contains("location")
                || v.contains("map")) return "Location";
        if (v.contains("city")
                || v.contains("office")
                || v.contains("building")
                || v.contains("tenant")) return "OfficeBuilding";
        if (v.contains("chart") || v.contains("bi") || v.contains("report")) return "TrendCharts";
        if (v.contains("data")) return "DataAnalysis";
        if (v.contains("hr") || v.contains("user")) return "UserFilled";
        if (v.contains("channel") || v.contains("avatar")) return "Avatar";
        if (v.contains("connection") || v.contains("integration") || v.contains("share"))
            return "Connection";
        if (v.contains("setting")
                || v.contains("setup")
                || v.contains("filter")
                || v.contains("role")) return "Setting";
        if (v.contains("order") || v.contains("list")) return "List";
        return "Document";
    }
}
