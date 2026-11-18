package com.rigour.merchant.application.port.out;
import com.rigour.merchant.api.v1.model.WecomModels.*;
import com.rigour.merchant.api.v1.model.StoreContactModels.Contact;
import com.rigour.merchant.application.service.StoreScope;
import java.time.Instant;
public interface WecomStore {
    StoreSummary storeSummary(StoreScope scope,String store);
    Summary summary(StoreScope scope,boolean mine,Instant from,Instant now);
    Page list(StoreScope scope,boolean mine,boolean pending,String cursor,int size);
    Contact bind(StoreScope scope,String identity,Bind command,Instant now);
    void accept(Snapshot snapshot,Instant now);
}
