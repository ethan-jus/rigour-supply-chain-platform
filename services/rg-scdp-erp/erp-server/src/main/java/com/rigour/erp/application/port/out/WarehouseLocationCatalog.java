package com.rigour.erp.application.port.out;

import com.rigour.settings.api.v1.model.AdminDivisionCatalog;

/** 仓库所在地使用公共行政区划目录，不查询其他服务数据库。 */
public interface WarehouseLocationCatalog {
    AdminDivisionCatalog published(String version);
}
