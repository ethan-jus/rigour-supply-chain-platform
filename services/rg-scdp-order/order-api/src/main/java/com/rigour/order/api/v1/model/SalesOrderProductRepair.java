package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 历史商品修复专用契约；交易快照不被标准计量覆盖，确认只能引用服务端预览。 */
public final class SalesOrderProductRepair {
    private SalesOrderProductRepair() { }

    public record LineCommand(Long lineId, String productCode, String skuCode, String bindingEvidence,
                              String sourceNamespace, String sourceCaptureRef, String sourceProductRecordId,
                              String sourceProductCode, String sourceEvidence, Boolean confirmSourceIdentity,
                              String historicalTransactionUnitCode, String transactionUnitEvidence,
                              Boolean confirmHistoricalTransactionUnit,
                              BigDecimal standardQuantity, String standardUnitCode, BigDecimal conversionFactor,
                              String conversionEvidence, Boolean confirmHistoricalConversion) { }

    public record PreviewCommand(Integer revision, String reason, List<LineCommand> lines) { }

    public record ApplyCommand(Boolean confirm) { }

    public record Candidate(Long productId, Long productVariantId, String productCode, String skuCode,
                            String productName, String specification, String unitCode,
                            Integer productRevision, Integer variantRevision,
                            Instant productUpdatedTime, Instant variantUpdatedTime) { }

    public record LinePreview(SalesOrderLineView original, Integer lineRevision, LineCommand requested,
                              List<Candidate> candidates, Candidate proposed, String sourceIdentityStatus, String transactionUnitStatus,
                              List<String> blockers) { }

    public record Preview(String previewId, Long orderId, String orderNo, Integer expectedRevision,
                          String status, Instant expiresAt, String reason, String createdBy,
                          Instant createdAt, List<LinePreview> lines) { }

    public record Applied(String previewId, Long orderId, Integer revision, String appliedBy,
                          Instant appliedAt, List<LinePreview> lines) { }

    public record CurrentLine(SalesOrderLineView original, Integer revision, Applied repair) { }

    public record Context(Long orderId, String orderNo, String sourceOrderNo, Integer revision,
                          List<CurrentLine> lines, List<Applied> recentRepairs) { }

    public record Evidence(Long orderId, String sourceOrderNo, Long lineId, Integer lineRevision,
                           Long productId, Long productVariantId, String productCode, String skuCode,
                           String productName, String specification, String sourceNamespace, String sourceCaptureRef,
                           String sourceProductRecordId, String sourceProductCode, String sourceEvidence,
                           String sourceIdentityStatus, String storedUnitCode, BigDecimal transactionQuantity,
                           String historicalTransactionUnitCode, String transactionUnitEvidence, String transactionUnitStatus,
                           BigDecimal standardQuantity, String standardUnitCode, BigDecimal conversionFactor,
                           String conversionEvidence, String bindingEvidence, String previewId, String appliedBy, Instant appliedAt) { }

    public record EvidencePage(List<Evidence> items, Long nextAfterLineId, boolean hasMore, Instant observedAt) { }
}
