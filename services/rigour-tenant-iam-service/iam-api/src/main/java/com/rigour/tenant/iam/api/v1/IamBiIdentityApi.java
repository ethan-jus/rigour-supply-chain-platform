package com.rigour.tenant.iam.api.v1;

import com.rigour.tenant.iam.api.v1.model.IamBiIdentityView;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** BI 源身份只读契约，普通账号仅可查询本人。 */
public interface IamBiIdentityApi {
    @GetMapping("/api/v1/iam/bi-identity")
    IamBiIdentityView identity(@RequestParam(required = false) UUID userId);
}
