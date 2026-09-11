package com.rigour.integration.application.port.out;

import java.util.List;
import java.util.Map;

/** 飞书多维表格只读端口；Integration 统一处理 Base 记录和附件下载。 */
public interface FeishuBitableClient {

    List<BitableTable> tables(String appToken);

    List<BitableField> fields(String appToken, String tableId);

    List<BitableRecord> records(String appToken, String tableId, String viewId);

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
