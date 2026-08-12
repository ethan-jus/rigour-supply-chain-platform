package com.rigour.integration.application.port.out;

import com.rigour.integration.application.port.out.DhbClient.Connector;
import com.rigour.integration.application.port.out.DhbClient.ProductImage;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductMediaSyncView;
import java.util.List;
import java.util.UUID;

/** 商品图片异步任务持久化端口；任务和图片明细均属于 Integration 自有 Schema。 */
public interface ProductMediaSyncStore {

    ProductMediaSyncView create(UUID tenantId, UUID actorId, UUID connectorId,
                                long totalImages, List<MediaItem> items);

    ProductMediaSyncView status(UUID tenantId, UUID connectorId, UUID jobId);

    /**
     * 查找同租户、同连接器、同来源图片快照且已经成功的最近对象；返回值仍需由 COS 适配器确认对象存在。
     */
    ReusableMedia findReusable(UUID tenantId, UUID connectorId, String sourceProductId,
                               ProductImage image);

    /** 只返回属于当前租户和连接器、且已成功上传的对象 Key。 */
    String completedObjectKey(UUID tenantId, UUID connectorId, UUID jobId,
                             String sourceProductId, String sourceResourceId, Integer sortOrder);

    List<ClaimedMediaItem> claimPending(int limit, int maxAttempts);

    void markSucceeded(UUID itemId, String objectKey, String contentType);

    void markFailed(UUID itemId, String errorCode, String errorMessage, boolean retryable,
                    int maxAttempts);

    record MediaItem(String sourceProductId, ProductImage image,
                     String reusableObjectKey, String reusableContentType) {
        public MediaItem(String sourceProductId, ProductImage image) {
            this(sourceProductId, image, null, null);
        }

        public boolean reused() {
            return reusableObjectKey != null && !reusableObjectKey.isBlank();
        }
    }

    record ReusableMedia(String objectKey, String contentType) {
    }

    record ClaimedMediaItem(UUID itemId, UUID jobId, UUID tenantId, UUID connectorId,
                            Connector connector, String sourceProductId, ProductImage image,
                            int attempts) {
    }
}
