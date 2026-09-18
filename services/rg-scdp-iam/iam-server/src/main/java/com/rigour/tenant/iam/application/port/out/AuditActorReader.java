package com.rigour.tenant.iam.application.port.out;
import java.util.*;
public interface AuditActorReader {
    record ActorName(String id, String employeeCode, String name) {}
    List<ActorName> actors(UUID tenant, List<String> references);
}
