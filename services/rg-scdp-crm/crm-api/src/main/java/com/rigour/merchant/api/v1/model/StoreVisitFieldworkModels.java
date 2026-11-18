package com.rigour.merchant.api.v1.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 拜访观察与正式合作事实分开；编码来自 Foundation 有效字典。 */
public final class StoreVisitFieldworkModels {
    private StoreVisitFieldworkModels() {}
    public record Progress(String businessLineCode,String intentionLevel,String cooperationStatus) {}
    public record Draft(long storeRevision,String businessStatus,String businessStatusReason,String businessTypeCode,
            String contactOutcome,List<String> contactIds,boolean noIntention,List<Progress> businesses,
            String noContactReason,String noContactDetail,String nextAction,LocalDate nextContactDate) {
        public Draft { contactIds=contactIds==null?List.of():List.copyOf(contactIds); businesses=businesses==null?List.of():List.copyOf(businesses); }
    }
    public record Accepted(Draft draft,String actorUserId,String employeeId,Map<String,String> labels,
                           List<StoreContactModels.Contact> contacts) {}
    public record Option(String code,String name) {}
    public record Form(long storeRevision,String businessStatus,String businessTypeCode,
                       List<Progress> businesses,Map<String,List<Option>> dictionaries,String syncStatus,Draft submitted,String visitStatus) {}
    public record Profile(String businessTypeCode,List<Progress> businesses,Map<String,List<Option>> dictionaries) {}
}
