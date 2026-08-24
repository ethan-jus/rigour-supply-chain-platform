package com.rigour.merchant.api;

import com.rigour.merchant.api.v1.model.SyncResult;
import com.rigour.merchant.application.service.CrmMasterDataSyncService;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ApiResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Integration 统一订货宝编排器调用 CRM 同步的内部入口。 */
@RestController
@RequestMapping("/internal/v1/crm/dhb")
public final class InternalCrmDhbSyncController {
    private final CrmMasterDataSyncService service;

    public InternalCrmDhbSyncController(CrmMasterDataSyncService service) {
        this.service = service;
    }

    @PostMapping("/sync")
    public ApiResponse<SyncResult> sync(@RequestBody InternalCrmDhbSyncCommand command) {
        if (command == null || command.connectorId() == null || command.sourceTaskId() == null) {
            throw new IllegalArgumentException("connectorId和sourceTaskId不能为空");
        }
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        return ApiResponse.success(service.runScheduled(caller, command.connectorId(),
                command.sourceTaskId(), command.maxPages() == null ? 100 : command.maxPages()));
    }

    public record InternalCrmDhbSyncCommand(UUID connectorId, UUID sourceTaskId, Integer maxPages) { }
}
