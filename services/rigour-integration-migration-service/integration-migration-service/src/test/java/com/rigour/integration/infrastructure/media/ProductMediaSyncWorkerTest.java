package com.rigour.integration.infrastructure.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rigour.integration.application.port.out.DhbClient;
import com.rigour.integration.application.port.out.DhbClient.Connector;
import com.rigour.integration.application.port.out.DhbClient.ProductImage;
import com.rigour.integration.application.port.out.ProductMediaStorage;
import com.rigour.integration.application.port.out.ProductMediaSyncStore;
import com.rigour.integration.application.port.out.ProductMediaSyncStore.ClaimedMediaItem;
import com.rigour.integration.application.service.dhb.ProductImageObjectKeyFactory;
import com.rigour.integration.infrastructure.config.ProductMediaProperties;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ProductMediaSyncWorkerTest {
    private static final UUID TENANT_ID = UUID.fromString("019fbaf9-cfb5-740d-b347-739d29765d8e");
    private static final UUID CONNECTOR_ID = UUID.fromString("019fbaf9-cfb5-740d-b347-739d29765d8f");
    private static final UUID JOB_ID = UUID.fromString("019fbaf9-cfb5-740d-b347-739d29765d90");
    private static final UUID ITEM_ID = UUID.fromString("019fbaf9-cfb5-740d-b347-739d29765d91");

    @Test
    void processesClaimedImageAndPersistsObjectKey() throws Exception {
        ProductMediaSyncStore store = mock(ProductMediaSyncStore.class);
        DhbClient client = mock(DhbClient.class);
        ProductMediaStorage storage = mock(ProductMediaStorage.class);
        ProductMediaProperties properties = new ProductMediaProperties();
        properties.setWorkerConcurrency(1);
        properties.setWorkerMaxAttempts(3);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch completed = new CountDownLatch(1);
        ProductImage image = new ProductImage("IMG-1", "P-1", "主图.png", "main.png", 1,
                "/000252/000252112/16/i20260810_97863700_943387.jpg");
        Connector connector = new Connector(TENANT_ID, CONNECTOR_ID, "https://erp.test", "secret-ref");
        ClaimedMediaItem item = new ClaimedMediaItem(ITEM_ID, JOB_ID, TENANT_ID, CONNECTOR_ID,
                connector, "P-1", image, 1);
        when(store.claimPending(1, 3)).thenReturn(List.of(item));
        when(client.downloadProductImage(connector, image.sourceUrl()))
                .thenReturn(new DhbClient.DownloadedImage(new byte[]{1, 2, 3}, "image/jpeg"));
        org.mockito.Mockito.doAnswer(invocation -> {
            completed.countDown();
            return null;
        }).when(store).markSucceeded(any(), any(), any());

        ProductMediaSyncWorker worker = new ProductMediaSyncWorker(store, client, storage,
                new ProductImageObjectKeyFactory("product-images"), properties, executor);
        worker.dispatch();

        assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
        verify(storage).put(eq(TENANT_ID.toString()),
                argThat(key -> key.contains("/product-images/P-1/IMG-1/")),
                eq("主图.png"), eq("image/jpeg"),
                argThat(bytes -> java.util.Arrays.equals(bytes, new byte[]{1, 2, 3})));
        verify(store).markSucceeded(eq(ITEM_ID),
                argThat(key -> key.contains("/product-images/P-1/IMG-1/")), eq("image/jpeg"));
        executor.shutdownNow();
    }
}
