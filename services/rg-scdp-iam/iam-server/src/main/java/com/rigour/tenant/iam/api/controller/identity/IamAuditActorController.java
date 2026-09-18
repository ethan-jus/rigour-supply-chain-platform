package com.rigour.tenant.iam.api.controller.identity;
import com.rigour.tenant.iam.application.service.identity.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
public final class IamAuditActorController {
    private final IdentityAccessService identity;
    private final AuditActorService actors;
    public IamAuditActorController(IdentityAccessService identity,AuditActorService actors){this.identity=identity;this.actors=actors;}
    @PostMapping("/api/v1/scdp/audit-actors/resolve")
    public Map<String,String> resolve(@RequestBody List<String> references){
        return actors.resolve(identity.currentUser(IamIdentityController.currentQuery()),references);
    }
}
