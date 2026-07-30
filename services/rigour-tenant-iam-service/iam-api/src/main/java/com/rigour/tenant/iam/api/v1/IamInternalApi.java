package com.rigour.tenant.iam.api.v1;

import com.rigour.tenant.iam.api.v1.model.IamAccessSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

/**
 * IAM提供给内部Java服务的低频查询契约。
 *
 * <p>调用方可以用OpenFeign实现此接口，但业务请求的鉴权热路径必须使用Gateway注入的可信上下文，
 * 不能逐请求同步调用IAM。</p>
 */
public interface IamInternalApi {

    String BASE_PATH = "/internal/v1/iam";

    /** 查询指定租户用户当前的访问快照；服务端仍必须校验调用方身份和租户边界。 */
    @GetMapping(BASE_PATH + "/tenants/{tenantId}/users/{userId}/access-snapshot")
    IamAccessSnapshot getAccessSnapshot(
            @PathVariable("tenantId") UUID tenantId,
            @PathVariable("userId") UUID userId
    );
}
