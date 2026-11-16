package com.rigour.sales.application.port.out;
import com.rigour.sales.api.v1.model.SalesVisitPolicyModels.*;
import java.util.*;
public interface SalesVisitPolicyReadRepository {
    List<Version> versions(UUID tenant);
    Snapshot snapshot(UUID tenant,UUID version);
}
