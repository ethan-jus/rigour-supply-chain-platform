package com.rigour.sales.api;

import com.rigour.sales.api.v1.SalesWorkAdminApi;
import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.AssignmentCommand;
import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.AssignmentView;
import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.FieldPolicyCommand;
import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.IdentityBindingCommand;
import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.IdentityBindingView;
import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.PolicyVersionView;
import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.SalesProfileCommand;
import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.SalesProfileView;
import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.StoreProjectionCommand;
import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.StoreProjectionView;
import com.rigour.sales.api.v1.model.SalesWorkAdminApiModels.VisitPolicyCommand;
import com.rigour.sales.application.service.SalesWorkAdminService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 销售管理维护 API：仅供销售管理后台/联调工具调用，不对 H5 开放。 */
@RestController
public final class SalesWorkAdminController {

    private final SalesWorkAdminService service;

    public SalesWorkAdminController(SalesWorkAdminService service) {
        this.service = service;
    }

    @PutMapping(SalesWorkAdminApi.IDENTITY_BINDING_PATH)
    public ApiResponse<IdentityBindingView> bindIdentity(@RequestBody IdentityBindingCommand command) {
        return ApiResponse.success(service.bindIdentity(command));
    }

    @PutMapping(SalesWorkAdminApi.SALES_PROFILE_PATH)
    public ApiResponse<SalesProfileView> upsertSalesProfile(@RequestBody SalesProfileCommand command) {
        return ApiResponse.success(service.upsertSalesProfile(command));
    }

    @PutMapping(SalesWorkAdminApi.FIELD_POLICY_PATH)
    public ApiResponse<PolicyVersionView> upsertFieldPolicy(@RequestBody FieldPolicyCommand command) {
        return ApiResponse.success(service.upsertFieldPolicy(command));
    }

    @PutMapping(SalesWorkAdminApi.VISIT_POLICY_PATH)
    public ApiResponse<PolicyVersionView> upsertVisitPolicy(@RequestBody VisitPolicyCommand command) {
        return ApiResponse.success(service.upsertVisitPolicy(command));
    }

    @PutMapping(SalesWorkAdminApi.STORE_PROJECTION_PATH)
    public ApiResponse<StoreProjectionView> upsertStoreProjection(
            @RequestBody StoreProjectionCommand command) {
        return ApiResponse.success(service.upsertStoreProjection(command));
    }

    @PutMapping(SalesWorkAdminApi.ASSIGNMENT_PATH)
    public ApiResponse<AssignmentView> upsertAssignment(@RequestBody AssignmentCommand command) {
        return ApiResponse.success(service.upsertAssignment(command));
    }
}
