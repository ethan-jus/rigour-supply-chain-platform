package com.rigour.merchant.application.port.out;

import com.rigour.merchant.api.v1.model.StoreContactModels.*;
import com.rigour.merchant.application.service.StoreScope;
import java.time.Instant;
import java.util.List;

public interface StoreContactStore {
    List<Contact> list(StoreScope scope,String store);
    Contact create(StoreScope scope,String store,Create command,Instant now);
    Contact update(StoreScope scope,String store,String contact,Update command,Instant now);
    Contact link(StoreScope scope,String store,Link command,Instant now);
    Contact addMethod(StoreScope scope,String store,String contact,AddMethod command,Instant now);
    Contact verifyMethod(StoreScope scope,String store,String contact,String method,VerifyMethod command,Instant now);
    List<Change> changes(StoreScope scope,String store,String contact);
    List<Contact> reception(StoreScope scope,String store,String visit);
    List<Contact> reception(StoreScope scope,String store,String visit,Reception command,Instant now);
}
