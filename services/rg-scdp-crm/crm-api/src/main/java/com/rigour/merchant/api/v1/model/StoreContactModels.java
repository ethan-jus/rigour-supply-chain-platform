package com.rigour.merchant.api.v1.model;

import java.time.Instant;
import java.util.List;

public final class StoreContactModels {
    private StoreContactModels() {}
    public record Method(String id,String type,String value,String status,String verificationMethod,
                         Instant verifiedAt,String verifiedBy,long revision) {}
    // Populated only by a future authenticated Integration projection, never by manual entry.
    public record WecomRelation(String identityScope,String externalUserId,String employeeId,
                                String status,Instant addedAt) {}
    public record Contact(String id,String name,long personRevision,String role,boolean primary,
                          String status,long revision,List<Method> methods,List<WecomRelation> wecomRelations) {}
    public record Create(String requestId,String name,String role,boolean primary,String phone,String wechatHint) {}
    public record Update(String requestId,long revision,long personRevision,String name,String role,
                         boolean primary,String status,String reason) {}
    public record Link(String requestId,String sourceStoreId,String contactId,String role,boolean primary,String reason) {}
    public record AddMethod(String requestId,String type,String value,String reason) {}
    public record VerifyMethod(String requestId,long revision,String status,String verificationMethod,String reason) {}
    public record Reception(String requestId,List<String> contactIds) {}
    public record Change(String id,String contactId,String operation,String actorUserId,String actorEmployeeId,
                         String reason,Instant occurredAt) {}
}
