package com.rigour.integration.api.v1;

import com.rigour.integration.api.v1.model.StaffPageView;
import com.rigour.integration.api.v1.model.StaffQueryCommand;
import com.rigour.integration.api.v1.model.StaffView;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** 订货宝员工目录 V1 HTTP 契约；CRM 只保存外部员工引用，不替代 HR 主档。 */
public interface DhbEmployeeApi {

    String BASE_PATH = "/api/v1/integration/dhb/employees";

    @PostMapping(BASE_PATH + "/{connectorId}/query")
    StaffPageView queryStaff(@PathVariable("connectorId") UUID connectorId,
                             @RequestBody(required = false) StaffQueryCommand command);

    @PostMapping(BASE_PATH + "/{connectorId}/{accountId}/query")
    StaffView queryStaffDetail(@PathVariable("connectorId") UUID connectorId,
                               @PathVariable("accountId") String accountId);
}
