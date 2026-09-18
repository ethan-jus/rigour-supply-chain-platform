package com.rigour.integration.application.port.out;

import java.util.List;
import java.util.Map;

/** 飞书多维表格只读端口；Integration 统一处理 Base 记录和附件下载。 */
public interface FeishuBitableClient {

    List<BitableTable> tables(String appToken);

    List<BitableField> fields(String appToken, String tableId);

    List<BitableRecord> records(String appToken, String tableId, String viewId);

    /** 对账专用有界分页；不回退到历史无边界 records 实现。 */
    default CaptureResult captureRecords(String appToken, String tableId, String viewId,
                                         FeishuCaptureBudget budget) {
        throw FeishuCaptureBudget.failure("UNSUPPORTED", "当前飞书客户端不支持完整分页采集");
    }

    record CapturedRecord(String recordId, Map<String, Object> fields, Long createdTime, Long lastModifiedTime) {
        public CapturedRecord(String recordId, Map<String, Object> fields) {
            this(recordId, fields, null, null);
        }
        public CapturedRecord {
            fields = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fields));
        }
    }

    record CaptureResult(List<CapturedRecord> rows, int pageCount, boolean complete) {
        public CaptureResult { rows = List.copyOf(rows); }
    }

    default DownloadedAttachment downloadAttachment(String fileToken, String fallbackFileName) {
        return downloadAttachment(fileToken, fallbackFileName, null, null, null);
    }

    DownloadedAttachment downloadAttachment(String fileToken, String fallbackFileName,
                                            String tableId, String recordId, String fieldId);

    record BitableTable(String tableId, String name) {
    }

    record BitableField(String fieldId, String name) {
    }

    record BitableRecord(String recordId, Map<String, Object> fields) {
        public BitableRecord {
            fields = fields == null ? Map.of() : Map.copyOf(fields);
        }
    }

    record DownloadedAttachment(String fileToken, String fileName, String contentType, byte[] content) {
        public DownloadedAttachment {
            content = content == null ? new byte[0] : content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
