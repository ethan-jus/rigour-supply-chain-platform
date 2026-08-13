package com.rigour.integration.api.controller.dhb;

import com.rigour.integration.api.v1.DhbEmployeeApi;
import com.rigour.integration.api.v1.model.StaffPageView;
import com.rigour.integration.api.v1.model.StaffQueryCommand;
import com.rigour.integration.api.v1.model.StaffView;
import com.rigour.integration.application.service.dhb.DhbIntegrationService;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 订货宝员工目录 HTTP 边界。 */
@RestController
@RequestMapping(DhbEmployeeApi.BASE_PATH)
public final class DhbEmployeeController implements DhbEmployeeApi {
    private final DhbIntegrationService service;

    public DhbEmployeeController(DhbIntegrationService service) {
        this.service = service;
    }

    @Override
    @PostMapping("/{connectorId}/query")
    public StaffPageView queryStaff(@PathVariable("connectorId") UUID connectorId,
                                    @RequestBody(required = false) StaffQueryCommand command) {
        return service.staff(connectorId, command);
    }

    @Override
    @PostMapping("/{connectorId}/{accountId}/query")
    public StaffView queryStaffDetail(@PathVariable("connectorId") UUID connectorId,
                                      @PathVariable("accountId") String accountId) {
        return service.staffInfo(connectorId, accountId);
    }
}
