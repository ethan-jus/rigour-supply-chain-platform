package com.rigour.hr.application.port.out;

import com.rigour.hr.api.v1.model.HrExpenseAttachmentView;

import java.util.List;

public interface CostAttachmentStore {
    void lock(String tenant);

    List<HrExpenseAttachmentView> list(String tenant, String entry);

    String fileKey(String tenant, String entry, String id);

    void add(
            String tenant,
            String entry,
            int revision,
            HrExpenseAttachmentView file,
            String fileKey);
}
