package com.rigour.erp.application.port.out;

import com.rigour.erp.api.v1.model.ErpCostAttachmentView;

import java.util.List;

public interface CostAttachmentStore {
    void lock(String tenant);

    void retire(String tenant, String entry, String id);

    void touch(String tenant, String entry, int revision, String actor);

    List<ErpCostAttachmentView> list(String tenant, String entry);

    String fileKey(String tenant, String entry, String id);

    void add(String tenant, String entry, int revision, ErpCostAttachmentView file, String fileKey);
}
