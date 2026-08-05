package com.rigour.integration.api.v1;

import com.rigour.integration.api.v1.model.DhbApiModels.OrderContentCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.OrderContentView;
import com.rigour.integration.api.v1.model.DhbApiModels.OrderMirrorView;
import com.rigour.integration.api.v1.model.DhbApiModels.OrderPageView;
import com.rigour.integration.api.v1.model.DhbApiModels.OrderQueryCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncRunCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncRunView;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/** 订货宝订单域 V1 HTTP 契约；查询、明细和第一阶段手动同步均归订单域。 */
public interface DhbOrderApi {

    String BASE_PATH = "/api/v1/integration/dhb/orders";
    String QUERY_PATH = BASE_PATH + "/{connectorId}/query";
    String CONTENT_PATH = BASE_PATH + "/{connectorId}/{orderNumber}/content";
    String SYNC_RUN_PATH = BASE_PATH + "/sync-tasks/{taskId}/run";
    String MIRRORS_PATH = BASE_PATH + "/mirrors";

    @PostMapping(QUERY_PATH)
    OrderPageView queryOrders(@PathVariable("connectorId") UUID connectorId,
                              @RequestBody(required = false) OrderQueryCommand command);

    @PostMapping(CONTENT_PATH)
    OrderContentView orderContent(@PathVariable("connectorId") UUID connectorId,
                                  @PathVariable("orderNumber") String orderNumber,
                                  @RequestBody(required = false) OrderContentCommand command);

    @PostMapping(SYNC_RUN_PATH)
    SyncRunView runOrderPull(@PathVariable("taskId") UUID taskId,
                             @RequestBody(required = false) SyncRunCommand command);

    @GetMapping(MIRRORS_PATH)
    List<OrderMirrorView> orderMirrors(
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset);
}
