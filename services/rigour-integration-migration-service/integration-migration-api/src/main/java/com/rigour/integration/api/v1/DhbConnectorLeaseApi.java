package com.rigour.integration.api.v1;

import com.rigour.integration.api.v1.model.DhbConnectorLeaseModels.LeaseCommand;
import com.rigour.integration.api.v1.model.DhbConnectorLeaseModels.LeaseView;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** 领域服务协调一整轮订货宝同步所使用的内部连接器租约契约。 */
public interface DhbConnectorLeaseApi {
    String BASE_PATH = "/internal/v1/integration/dhb/connector-leases";

    @PostMapping(BASE_PATH + "/{connectorId}")
    LeaseView acquire(@PathVariable UUID connectorId, @RequestBody LeaseCommand command);

    @PostMapping(BASE_PATH + "/{connectorId}/{token}/renew")
    LeaseView renew(@PathVariable UUID connectorId, @PathVariable String token);

    @DeleteMapping(BASE_PATH + "/{connectorId}/{token}")
    void release(@PathVariable UUID connectorId, @PathVariable String token);
}
