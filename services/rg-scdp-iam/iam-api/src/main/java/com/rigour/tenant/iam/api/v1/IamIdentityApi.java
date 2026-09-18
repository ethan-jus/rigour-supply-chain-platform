package com.rigour.tenant.iam.api.v1;

import com.rigour.tenant.iam.api.v1.model.CurrentUserView;
import org.springframework.web.bind.annotation.GetMapping;

/** SCDP读取当前登录人和已授权应用的外部V1契约。 */
public interface IamIdentityApi {

    @GetMapping("/api/v1/me")
    CurrentUserView getCurrentUser();

}
