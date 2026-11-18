package com.rigour.merchant.api.v1.model;

import java.time.Instant;
import java.util.List;

public final class WecomModels {
    private WecomModels() {}
    public record Store(String id,String name,String role) {}
    public record Person(String id,String contactId,String name,boolean linked,List<Store> stores,
                         List<StoreContactModels.WecomRelation> relations) {}
    public record Page(long total,List<Person> items,String nextCursor) {}
    public record Summary(String dataStatus,Instant syncedAt,Long activeContacts,Long linkedContacts,
                          Long pendingContacts,Long coveredStores,Long newContacts) {}
    public record StoreSummary(long contacts,long phoneContacts,Long wecomContacts,String dataStatus) {}
    public record Bind(String requestId,String storeId,String contactId,String name,String role,String reason) {}
    // Integration supplies a fully paged official snapshot and an explicitly mapped HR employee.
    // BASELINE / TRANSFER / RETURN are never acquisition credit. Missing rows end active relations.
    public record CustomerFact(String externalUserId,String name,Instant addedAt,String origin) {}
    public record Snapshot(String tenantId,String identityScope,String employeeId,String employeeName,
                           long sequence,Instant observedAt,boolean complete,List<CustomerFact> customers) {}
}
