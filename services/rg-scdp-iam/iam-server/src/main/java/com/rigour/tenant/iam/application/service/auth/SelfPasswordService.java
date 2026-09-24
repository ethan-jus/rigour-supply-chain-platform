package com.rigour.tenant.iam.application.service.auth;

import com.rigour.shared.core.exception.RequestValidationException;
import com.rigour.tenant.iam.application.port.out.SelfPasswordStore;
import com.rigour.tenant.iam.domain.model.settings.MemberPasswordPolicy;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public final class SelfPasswordService {
    private final SelfPasswordStore store;

    public SelfPasswordService(SelfPasswordStore store) { this.store = store; }

    public void change(String scope, UUID tenantId, UUID userId, String currentPassword, String newPassword) {
        if (!"TENANT".equals(scope) || tenantId == null || userId == null)
            throw new AccessDeniedException("需要有效的企业登录账号");
        if (currentPassword == null || currentPassword.isEmpty() || currentPassword.length() > 128)
            throw new RequestValidationException("请输入原密码");
        MemberPasswordPolicy.validate(newPassword);
        if (currentPassword.equals(newPassword)) throw new RequestValidationException("新密码不能与原密码相同");
        store.change(tenantId, userId, currentPassword, newPassword);
    }
}
