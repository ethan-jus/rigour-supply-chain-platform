package com.rigour.erp.infrastructure.media;

import com.rigour.erp.infrastructure.config.ProductMediaAccessProperties;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CosCostAttachmentStorageTest {
    @Test void sourceDirectoryAndMetadataIdentifyOwningServiceAndCostEntry() {
        String key=CosCostAttachmentStorage.objectKey("tenant-a", "entry-1", "image/jpeg");
        assertThat(key).startsWith("tenant-a/erp-cost-attachments/entry-1/").endsWith(".jpg");
        var metadata=CosCostAttachmentStorage.objectMetadata("entry-1", "image/jpeg", 100);
        assertThat(metadata.getUserMetadata()).containsEntry("source-system", "ERP")
                .containsEntry("business-type", "cost-attachment").containsEntry("cost-entry-id", "entry-1");
        assertThat(metadata.getContentLength()).isEqualTo(100);
        assertThat(metadata.getContentType()).isEqualTo("image/jpeg");
    }
    @Test void missingStorageConfigurationIsActionableAndMakesNoNetworkRequest() {
        var properties=new ProductMediaAccessProperties();
        var storage=new CosCostAttachmentStorage(properties);
        assertThatThrownBy(()->storage.upload("tenant-a", "entry-1", "image/jpeg", new byte[]{1}))
                .isInstanceOfSatisfying(BusinessException.class,
                        error->assertThat(error.getErrorCode()).isEqualTo(ErrorCode.COST_ATTACHMENT_STORAGE_UNAVAILABLE))
                .hasMessageContaining("请联系管理员");
    }
}
