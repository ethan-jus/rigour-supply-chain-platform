package com.rigour.order.infrastructure.ocr;
import static org.mockito.Mockito.*;
import com.rigour.order.infrastructure.persistence.repository.JdbcVoucherRecognitionStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
class VoucherRecognitionWorkerTest {
    @Test void devDiscoveryOffDoesNotMaintainHistoricalData() {
        var store=mock(JdbcVoucherRecognitionStore.class);
        var worker=new VoucherRecognitionWorker(store,mock(ObjectProvider.class),"python3");
        ReflectionTestUtils.setField(worker,"discoverEnabled",false);
        worker.discover(); verifyNoInteractions(store);
        ReflectionTestUtils.setField(worker,"discoverEnabled",true);
        worker.discover(); verify(store).discoverUnrecognized(100);
    }
}
