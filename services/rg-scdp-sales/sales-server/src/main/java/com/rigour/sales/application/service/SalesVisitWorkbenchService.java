package com.rigour.sales.application.service;

import com.rigour.merchant.api.v1.model.StoreVisitQueryModels.Page;
import com.rigour.sales.application.port.out.CrmStoreVisitClient;
import com.rigour.shared.context.AuthorizationContext;
import java.time.LocalDate;
import org.springframework.stereotype.Service;

/** App 拜访工作台读取入口；正式拜访事实及有效性始终由 CRM 提供，不重复持久化。 */
@Service
public class SalesVisitWorkbenchService {
    private final CrmStoreVisitClient crm;
    public SalesVisitWorkbenchService(CrmStoreVisitClient crm){this.crm=crm;}
    public Page visits(LocalDate from,LocalDate to,String kind,String status,String keyword,int page,int size) {
        AuthorizationContext.requirePermission("sales:visit:own:read");
        return crm.ownVisits(from,to,kind,status,keyword,page,size);
    }
}
