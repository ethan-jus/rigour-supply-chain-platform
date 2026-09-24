package com.rigour.hr.application.port.out;
import com.rigour.hr.api.v1.model.*;
import java.util.List;
public interface HrDhbBindingReviewStore {
    List<DhbBindingReviewView> pending(String tenant);
    void confirm(String tenant, long bindingId, DhbBindingReviewCommand command, String actor);
}
