package com.rigour.erp.api.v1;

import com.rigour.erp.api.v1.model.ErpDataSyncCommand;
import com.rigour.erp.api.v1.model.ErpDataSyncResult;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** ERP V1 统一数据同步契约；商品与供应链对象共用一个入口。 */
public interface ErpDataSyncApi {
    String SYNC_PATH = "/api/v1/erp/sync";

    /**
     * 同步一类订货宝数据到 ERP 本地业务表。
     *
     * @param command 同步对象类型和最大页数；不接收 Connector、Token 或 Secret
     * @return 同步批次、连接器及新增/变更/重复/拒绝统计
     */
    @PostMapping(SYNC_PATH)
    ApiResponse<ErpDataSyncResult> sync(
            @RequestBody(required = false) ErpDataSyncCommand command);
}
