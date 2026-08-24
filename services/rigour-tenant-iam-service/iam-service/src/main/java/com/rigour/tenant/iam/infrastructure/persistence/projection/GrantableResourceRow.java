package com.rigour.tenant.iam.infrastructure.persistence.projection;

import java.util.UUID;

/** 当前租户可授权资源查询行，包含应用归属信息用于前端分组展示。 */
public final class GrantableResourceRow {
    private UUID id;
    private UUID applicationId;
    private String applicationCode;
    private String applicationName;
    private UUID parentId;
    private String code;
    private String type;
    private String permissionCode;
    private String displayName;
    private int sortOrder;
    private String status;
    private String routeKey;
    private String routePath;
    private String iconKey;
    private boolean visible;
    private boolean keepAlive;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getApplicationId() { return applicationId; }
    public void setApplicationId(UUID applicationId) { this.applicationId = applicationId; }
    public String getApplicationCode() { return applicationCode; }
    public void setApplicationCode(String applicationCode) { this.applicationCode = applicationCode; }
    public String getApplicationName() { return applicationName; }
    public void setApplicationName(String applicationName) { this.applicationName = applicationName; }
    public UUID getParentId() { return parentId; }
    public void setParentId(UUID parentId) { this.parentId = parentId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getPermissionCode() { return permissionCode; }
    public void setPermissionCode(String permissionCode) { this.permissionCode = permissionCode; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRouteKey() { return routeKey; }
    public void setRouteKey(String routeKey) { this.routeKey = routeKey; }
    public String getRoutePath() { return routePath; }
    public void setRoutePath(String routePath) { this.routePath = routePath; }
    public String getIconKey() { return iconKey; }
    public void setIconKey(String iconKey) { this.iconKey = iconKey; }
    public boolean isVisible() { return visible; }
    public void setVisible(boolean visible) { this.visible = visible; }
    public boolean isKeepAlive() { return keepAlive; }
    public void setKeepAlive(boolean keepAlive) { this.keepAlive = keepAlive; }
}
