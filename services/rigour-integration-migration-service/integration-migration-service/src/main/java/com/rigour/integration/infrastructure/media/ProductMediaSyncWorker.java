package com.rigour.integration.infrastructure.media;

import com.rigour.integration.application.port.out.DhbClient;
import com.rigour.integration.application.port.out.DhbClient.DownloadedImage;
import com.rigour.integration.application.port.out.ProductMediaStorage;
import com.rigour.integration.application.port.out.ProductMediaSyncStore;
import com.rigour.integration.application.port.out.ProductMediaSyncStore.ClaimedMediaItem;
import com.rigour.integration.application.service.dhb.ProductImageObjectKeyFactory;
import com.rigour.integration.infrastructure.config.ProductMediaProperties;
import com.rigour.integration.infrastructure.dhb.DhbClientException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** 商品图片持久化任务消费者；固定并发、可重试、可在实例重启后继续处理。 */
public final class ProductMediaSyncWorker {
    private static final Logger log = LoggerFactory.getLogger(ProductMediaSyncWorker.class);

    private final ProductMediaSyncStore store;
    private final DhbClient client;
    private final ProductMediaStorage storage;
    private final ProductImageObjectKeyFactory keyFactory;
    private final ProductMediaProperties properties;
    private final ExecutorService executor;
    private final AtomicInteger inFlight = new AtomicInteger();

    public ProductMediaSyncWorker(ProductMediaSyncStore store, DhbClient client,
                                  ProductMediaStorage storage,
                                  ProductImageObjectKeyFactory keyFactory,
                                  ProductMediaProperties properties, ExecutorService executor) {
        this.store = Objects.requireNonNull(store, "商品图片任务存储不能为空");
        this.client = Objects.requireNonNull(client, "订货宝客户端不能为空");
        this.storage = Objects.requireNonNull(storage, "商品图片存储不能为空");
        this.keyFactory = Objects.requireNonNull(keyFactory, "商品图片 Key 工厂不能为空");
        this.properties = Objects.requireNonNull(properties, "商品图片配置不能为空");
        this.executor = Objects.requireNonNull(executor, "商品图片线程池不能为空");
    }

    @Scheduled(fixedDelayString = "${rigour.integration.product-media.worker-poll-interval-ms:1000}")
    public void dispatch() {
        int available = properties.getWorkerConcurrency() - inFlight.get();
        if (available <= 0) return;
        List<ClaimedMediaItem> items = store.claimPending(available, properties.getWorkerMaxAttempts());
        for (ClaimedMediaItem item : items) {
            inFlight.incrementAndGet();
            try {
                executor.execute(() -> process(item));
            } catch (RuntimeException rejected) {
                inFlight.decrementAndGet();
                store.markFailed(item.itemId(), "MEDIA_WORKER_REJECTED",
                        "图片任务线程池暂时无法接收任务", true, properties.getWorkerMaxAttempts());
            }
        }
    }

    private void process(ClaimedMediaItem item) {
        try {
            DownloadedImage downloaded = client.downloadProductImage(item.connector(), item.image().sourceUrl());
            String objectKey = keyFactory.generate(item.tenantId().toString(), item.sourceProductId(),
                    item.image().sourceResourceId(), item.image().sortOrder(), downloaded.content(),
                    item.image().fileName(), downloaded.contentType());
            storage.put(item.tenantId().toString(), objectKey, item.image().originalName(),
                    downloaded.contentType(), downloaded.content());
            store.markSucceeded(item.itemId(), objectKey, downloaded.contentType());
            log.info("订货宝商品图片异步上传完成 tenantId={} connectorId={} jobId={} sourceProductId={} sourceResourceId={} objectKey={}",
                    item.tenantId(), item.connectorId(), item.jobId(), item.sourceProductId(),
                    item.image().sourceResourceId(), objectKey);
        } catch (RuntimeException error) {
            boolean retryable = !(error instanceof DhbClientException dhb) || dhb.retryable();
            String errorCode = error instanceof DhbClientException dhb
                    ? dhb.code() : error.getClass().getSimpleName();
            try {
                store.markFailed(item.itemId(), errorCode, safeMessage(error), retryable,
                        properties.getWorkerMaxAttempts());
            } catch (RuntimeException persistError) {
                // 明细仍保持 RUNNING，由 claimPending 的超时回收逻辑重新领取，不能让线程直接退出。
                log.error("订货宝商品图片失败状态写入异常，将由超时回收后重试 tenantId={} connectorId={} jobId={} sourceProductId={} sourceResourceId={} errorType={}",
                        item.tenantId(), item.connectorId(), item.jobId(), item.sourceProductId(),
                        item.image().sourceResourceId(), persistError.getClass().getSimpleName(), persistError);
            }
            log.warn("订货宝商品图片异步上传失败 tenantId={} connectorId={} jobId={} sourceProductId={} sourceResourceId={} errorType={} retryable={}",
                    item.tenantId(), item.connectorId(), item.jobId(), item.sourceProductId(),
                    item.image().sourceResourceId(), errorCode, retryable);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return "商品图片上传失败";
        String oneLine = message.replace('\r', ' ').replace('\n', ' ');
        return oneLine.length() > 2000 ? oneLine.substring(0, 2000) : oneLine;
    }
}
