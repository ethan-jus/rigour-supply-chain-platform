package com.rigour.analytics.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 对账只读快照契约；上传时间不代表在线修改时间，空金额不代表零。 */
public record BiReconciliationReview(
        String id, Instant from, Instant to, Instant capturedAt, Instant completedAt,
        Version sourceVersion, Version previousVersion, String onlineStatus,
        String status, boolean sourceDeclaredComplete, Instant sourceExportedAt,
        List<String> notices, List<Row> rows) {
    public record Command(String batchId, String previousBatchId, Instant from, Instant to,
                          Instant sourceExportedAt, boolean sourceDeclaredComplete, String onlineCaptureId) {
        /** 已有文件复核调用保留六参数构造；在线采集与文件批次必须二选一。 */
        public Command(String batchId, String previousBatchId, Instant from, Instant to,
                       Instant sourceExportedAt, boolean sourceDeclaredComplete) {
            this(batchId, previousBatchId, from, to, sourceExportedAt, sourceDeclaredComplete, null);
        }
    }
    public record Version(String batchId, String fileName, String checksum, String sourceUrl,
                          Instant uploadedAt, String importStatus, long rowCount, OnlineEvidence onlineEvidence) {
        /** 历史文件版本无在线证据，旧持久化 JSON 缺字段时仍可读取。 */
        public Version(String batchId, String fileName, String checksum, String sourceUrl,
                       Instant uploadedAt, String importStatus, long rowCount) {
            this(batchId, fileName, checksum, sourceUrl, uploadedAt, importStatus, rowCount, null);
        }
    }
    /** 完整分页不等于原子快照；仅代表所列采集窗口内读取到的来源。 */
    public record OnlineEvidence(String captureId, String sourceId, String sourceName, String sourceUrl,
                                 Instant startedAt, Instant completedAt, boolean complete, boolean filtered,
                                 int pageCount, int recordCount, boolean atomic) { }
    public record History(String id, String fileName, Instant capturedAt, String status) { }
    public record Fact(String key, String orderNo, String kind, String city, String sales,
                       String customer, String product, String specification, String unit,
                       Instant orderDate, BigDecimal amount, BigDecimal paid, BigDecimal unpaid,
                       BigDecimal quantity, Instant updatedAt, String sourceRows,
                       boolean excludedRefund, List<String> uncertainties, String rawUnit, String unitCode,
                       String unitEvidence, String associationEvidence, String sourceRecordId,
                       String sourceProductId, String sourceProductCode, String systemLineId,
                       String systemVariantId, String confirmedUnitCode) {
        public Fact(String key, String orderNo, String kind, String city, String sales,
                    String customer, String product, String specification, String unit,
                    Instant orderDate, BigDecimal amount, BigDecimal paid, BigDecimal unpaid,
                    BigDecimal quantity, Instant updatedAt, String sourceRows, boolean excludedRefund,
                    List<String> uncertainties, String rawUnit, String unitCode, String unitEvidence,
                    String associationEvidence, String sourceRecordId, String sourceProductId, String sourceProductCode) {
            this(key, orderNo, kind, city, sales, customer, product, specification, unit, orderDate, amount,
                    paid, unpaid, quantity, updatedAt, sourceRows, excludedRefund, uncertainties, rawUnit, unitCode,
                    unitEvidence, associationEvidence, sourceRecordId, sourceProductId, sourceProductCode, null, null, null);
        }
        /** 旧构造仅用于明确给出单位的事实；历史 JSON 未提供证据字段时保持为空。 */
        public Fact(String key, String orderNo, String kind, String city, String sales,
                    String customer, String product, String specification, String unit,
                    Instant orderDate, BigDecimal amount, BigDecimal paid, BigDecimal unpaid,
                    BigDecimal quantity, Instant updatedAt, String sourceRows,
                    boolean excludedRefund, List<String> uncertainties, String rawUnit, String unitCode) {
            this(key, orderNo, kind, city, sales, customer, product, specification, unit, orderDate, amount,
                    paid, unpaid, quantity, updatedAt, sourceRows, excludedRefund, uncertainties, rawUnit, unitCode,
                    unit == null ? "UNKNOWN" : "EXPLICIT", key.startsWith("UNLINKED|") ? "UNLINKED" : "SYSTEM",
                    null, null, null);
        }
        /** 历史 JSON 和构造兼容；新证据区分来源原单位、字典编码及中文展示名。 */
        public Fact(String key, String orderNo, String kind, String city, String sales,
                    String customer, String product, String specification, String unit,
                    Instant orderDate, BigDecimal amount, BigDecimal paid, BigDecimal unpaid,
                    BigDecimal quantity, Instant updatedAt, String sourceRows,
                    boolean excludedRefund, List<String> uncertainties) {
            this(key, orderNo, kind, city, sales, customer, product, specification, unit, orderDate, amount,
                    paid, unpaid, quantity, updatedAt, sourceRows, excludedRefund, uncertainties, null, null);
        }
    }
    public record Row(String key, String orderNo, String kind, String city, String sales,
                      String status, String versionStatus, Fact source, Fact business, Fact bi,
                      Fact previous, List<String> issues, String financialStatus, String quantityStatus,
                      String associationStatus) {
        /** 旧版本记录没有分项结论，不能将旧总状态冒充已单独核验。 */
        public Row(String key, String orderNo, String kind, String city, String sales,
                   String status, String versionStatus, Fact source, Fact business, Fact bi,
                   Fact previous, List<String> issues) {
            this(key, orderNo, kind, city, sales, status, versionStatus, source, business, bi,
                    previous, issues, null, null, null);
        }
    }
    public record Summary(long count, long matched, long differences, long unverified, long excluded,
                          BigDecimal sourceAmount, BigDecimal businessAmount, BigDecimal biAmount,
                          BigDecimal sourcePaid, BigDecimal businessPaid, BigDecimal biPaid) { }
    public record Dimension(String name, Summary summary) { }
    public record Page(String id, Instant from, Instant to, Instant capturedAt, Instant completedAt,
                       Version sourceVersion, Version previousVersion, String onlineStatus,
                       String status, boolean sourceDeclaredComplete, Instant sourceExportedAt,
                       List<String> notices, Summary summary, List<Dimension> cities,
                       List<Dimension> sales, long total, int page, int pageSize, List<Row> rows) { }
}
