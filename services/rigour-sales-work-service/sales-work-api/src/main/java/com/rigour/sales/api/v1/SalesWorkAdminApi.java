package com.rigour.sales.api.v1;

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
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 销售管理维护契约：销售画像、身份绑定、规则发布和 CRM 投影的写入入口。
 * 仅供销售管理后台/联调工具调用，权限在 IAM V30 登记；不对 H5 开放。
 */
public interface SalesWorkAdminApi {

    String ADMIN_BASE_PATH = SalesWorkApi.BASE_PATH + "/admin";
    String IDENTITY_BINDING_PATH = ADMIN_BASE_PATH + "/identity-bindings";
    String SALES_PROFILE_PATH = ADMIN_BASE_PATH + "/sales-profiles";
    String FIELD_POLICY_PATH = ADMIN_BASE_PATH + "/field-policies";
    String VISIT_POLICY_PATH = ADMIN_BASE_PATH + "/visit-policies";
    String STORE_PROJECTION_PATH = ADMIN_BASE_PATH + "/store-projections";
    String ASSIGNMENT_PATH = ADMIN_BASE_PATH + "/assignments";

    @PutMapping(IDENTITY_BINDING_PATH)
    ApiResponse<IdentityBindingView> bindIdentity(@RequestBody IdentityBindingCommand command);

    @PutMapping(SALES_PROFILE_PATH)
    ApiResponse<SalesProfileView> upsertSalesProfile(@RequestBody SalesProfileCommand command);

    @PutMapping(FIELD_POLICY_PATH)
    ApiResponse<PolicyVersionView> upsertFieldPolicy(@RequestBody FieldPolicyCommand command);

    @PutMapping(VISIT_POLICY_PATH)
    ApiResponse<PolicyVersionView> upsertVisitPolicy(@RequestBody VisitPolicyCommand command);

    @PutMapping(STORE_PROJECTION_PATH)
    ApiResponse<StoreProjectionView> upsertStoreProjection(@RequestBody StoreProjectionCommand command);

    @PutMapping(ASSIGNMENT_PATH)
    ApiResponse<AssignmentView> upsertAssignment(@RequestBody AssignmentCommand command);
}
