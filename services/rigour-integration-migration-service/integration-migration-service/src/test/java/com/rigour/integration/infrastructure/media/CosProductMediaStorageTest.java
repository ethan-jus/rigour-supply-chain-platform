package com.rigour.integration.infrastructure.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import com.rigour.integration.infrastructure.config.ProductMediaProperties;
import java.net.SocketException;
import org.junit.jupiter.api.Test;

class CosProductMediaStorageTest {
    private static final String TENANT_ID = "019fbaf9-cfb5-740d-b347-739d29765d8e";
    private static final String OBJECT_KEY = TENANT_ID + "/product-images/P-1/IMG-1/hash.png";
    private static final String FUND_ATTACHMENT_KEY =
            TENANT_ID + "/fund-attachments/FR_20260826_0247/ATT/hash.png";

    @Test
    void usesShortConnectionAndApplicationManagedRetryForCosUploadRequests() {
        ClientConfig config = CosProductMediaStorage.createClientConfig(properties().getCos());

        assertThat(config.isShortConnection()).isTrue();
        assertThat(config.getMaxErrorRetry()).isZero();
        assertThat(config.getSocketTimeout()).isEqualTo(60_000);
    }

    @Test
    void retriesTransientCosUploadWithFreshRequestBody() {
        COSClient client = mock(COSClient.class);
        when(client.doesObjectExist("bucket", OBJECT_KEY)).thenReturn(false);
        when(client.putObject(any(PutObjectRequest.class)))
                .thenAnswer(invocation -> {
                    PutObjectRequest request = invocation.getArgument(0);
                    assertThat(request.getFile()).isFile();
                    assertThat(request.getFile().length()).isEqualTo(3);
                    assertThat(request.getMetadata().getUserMetadata()).isEmpty();
                    throw new CosClientException(new SocketException("broken pipe"));
                })
                .thenAnswer(invocation -> {
                    PutObjectRequest request = invocation.getArgument(0);
                    assertThat(request.getFile()).isFile();
                    assertThat(request.getFile().length()).isEqualTo(3);
                    assertThat(request.getMetadata().getUserMetadata()).isEmpty();
                    return new PutObjectResult();
                });

        CosProductMediaStorage storage = new CosProductMediaStorage(properties(), client);

        storage.put(TENANT_ID, OBJECT_KEY, "主图-2.png", "image/png", new byte[]{1, 2, 3});

        verify(client, times(2)).putObject(any(PutObjectRequest.class));
    }

    @Test
    void skipsUploadWhenCosObjectAlreadyExists() {
        COSClient client = mock(COSClient.class);
        when(client.doesObjectExist("bucket", OBJECT_KEY)).thenReturn(true);

        CosProductMediaStorage storage = new CosProductMediaStorage(properties(), client);

        storage.put(TENANT_ID, OBJECT_KEY, "main.png", "image/png", new byte[]{1, 2, 3});

        verify(client, never()).putObject(any(PutObjectRequest.class));
    }

    @Test
    void exposesCosExistenceCheckForDatabaseSnapshotReuse() {
        COSClient client = mock(COSClient.class);
        when(client.doesObjectExist("bucket", OBJECT_KEY)).thenReturn(true);

        CosProductMediaStorage storage = new CosProductMediaStorage(properties(), client);

        assertThat(storage.exists(TENANT_ID, OBJECT_KEY)).isTrue();
        verify(client).doesObjectExist("bucket", OBJECT_KEY);
    }

    @Test
    void allowsFundAttachmentPrefixInSharedCosBucket() {
        COSClient client = mock(COSClient.class);
        when(client.doesObjectExist("bucket", FUND_ATTACHMENT_KEY)).thenReturn(true);

        CosProductMediaStorage storage = new CosProductMediaStorage(properties(), client);

        assertThat(storage.exists(TENANT_ID, FUND_ATTACHMENT_KEY)).isTrue();
        verify(client).doesObjectExist("bucket", FUND_ATTACHMENT_KEY);
    }

    private static ProductMediaProperties properties() {
        ProductMediaProperties properties = new ProductMediaProperties();
        ProductMediaProperties.Cos cos = properties.getCos();
        cos.setRegion("ap-beijing");
        cos.setBucket("bucket");
        cos.setSecretId("secret-id");
        cos.setSecretKey("secret-key");
        return properties;
    }
}
