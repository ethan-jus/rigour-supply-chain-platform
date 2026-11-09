package com.rigour.sales.application.port.out;
import java.util.UUID;
public interface CrmVisitEvidenceOutbox {
    void enqueue(UUID tenant,UUID capture);
}
