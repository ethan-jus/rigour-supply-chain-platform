package com.rigour.erp.domain.model.supply;

import java.math.BigDecimal;

/** ERP 仓库导入模型。 */
public record Warehouse(String sourceId, String sourceGuid, String code, String name,
                        String sourceStatus, Boolean defaultFlag, BigDecimal acreage,
                        String phoneMasked, String address, String collaboratorSourceId,
                        String remark, String payloadHash) { }
