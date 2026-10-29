package com.rigour.merchant.application.port.out;
import com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.*;
public interface StoreVisitCaptureClient {
    Policy policy();
    Facts facts(String tenant,String store,String ownership);
}
