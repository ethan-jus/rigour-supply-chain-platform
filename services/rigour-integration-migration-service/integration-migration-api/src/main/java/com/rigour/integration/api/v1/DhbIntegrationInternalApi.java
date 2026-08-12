package com.rigour.integration.api.v1;

import com.rigour.integration.api.v1.model.DhbApiModels.SyncTargetView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Integration与Order Center之间的内部订货宝调度契约。
 *
 * <p>该路径不属于Gateway的浏览器路由，只接受带可信服务身份签名的直接服务调用。</p>
 */
public interface DhbIntegrationInternalApi {

    String BASE_PATH = "/internal/v1/integration/dhb";
    String SYNC_TARGETS_PATH = BASE_PATH + "/sync-targets";

    @GetMapping(SYNC_TARGETS_PATH)
    List<SyncTargetView> syncTargets(
            @RequestParam(name = "objectType", defaultValue = "ORDER") String objectType);
}
