package com.rigour.merchant.api.v1.model;
import java.time.Instant;
import java.util.List;

public final class StoreVisitVerificationModels {
    private StoreVisitVerificationModels() {}
    public record AppealCommand(long revision,String requestId,String reason) {}
    public record Appeal(String id,String status,String reason,String originalState,String originalReason,
            Instant submittedAt,String decision,String reviewNote,Instant reviewedAt) {}
    public record Verification(String visitId,String state,String reason,long revision,boolean canAppeal,List<Appeal> appeals) {}
    public record QueueRow(String storeId,String storeName,String visitId,String captureRef,String employeeCode,
            Instant visitedAt,Appeal appeal) {}
    public record Queue(List<QueueRow> items,long total,int page,int pageSize) {}
}
