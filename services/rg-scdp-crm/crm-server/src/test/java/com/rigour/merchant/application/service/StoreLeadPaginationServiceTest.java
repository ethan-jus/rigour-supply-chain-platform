package com.rigour.merchant.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.merchant.api.v1.model.StoreModels.*;
import com.rigour.merchant.application.port.out.*;
import com.rigour.settings.api.v1.model.AdminDivisionCatalog;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.exception.RequestValidationException;
import com.rigour.tenant.iam.api.v1.model.StoreAccessView;
import java.util.*;
import org.junit.jupiter.api.Test;

class StoreLeadPaginationServiceTest {
    final StoreReferenceClient references=mock(StoreReferenceClient.class);
    final StorePoolStore store=mock(StorePoolStore.class);
    final StorePoolService service=new StorePoolService(references,mock(CrmEmployeeClient.class),store,mock(StorePoiClient.class));

    void authorize(String action) {
        when(references.access(action)).thenReturn(new StoreAccessView("T","U",action,1,null,null,"管理员",null,Set.of(action),
                List.of(new StoreAccessView.Grant("ALL",List.of(),false,true,List.of())),false,List.of()));
        when(references.divisions()).thenReturn(new AdminDivisionCatalog("V","test","test",List.of(
                new AdminDivisionCatalog.Node("330000","浙江省","PROVINCE",null,"EAST"),
                new AdminDivisionCatalog.Node("330100","杭州市","PREFECTURE","330000",null),
                new AdminDivisionCatalog.Node("330105","拱墅区","COUNTY","330100",null))));
    }
    @Test void validatesPageSizeKeywordAndStateBeforeResolvingAccessOrQuerying() {
        for(int[] pair:List.of(new int[]{0,20},new int[]{-1,20},new int[]{1,0},new int[]{1,-1},new int[]{1,101}))
            assertThatThrownBy(()->service.leads(false,pair[0],pair[1],null,null)).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(()->service.leads(false,1,20,"字".repeat(101),null)).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(()->service.leads(false,1,20,null,"UNKNOWN")).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(()->service.leads(false,1,20,null,"WITHDRAWN")).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(()->service.leads(true,1,20,null,"APPROVED")).isInstanceOf(RequestValidationException.class).hasMessageContaining("待审核");
        assertThatThrownBy(()->service.leads(true,1,20,null,"REJECTED")).isInstanceOf(RequestValidationException.class);
        verifyNoInteractions(references,store);
    }
    @Test void personalListUsesReadPermissionAndReturnsPageMetadata() {
        authorize("crm:lead:read");
        var result=new LeadPage(42,List.of(),3,20);
        when(store.leads(any(),eq(false),eq(3),eq(20),eq("50%_!"),eq("REJECTED"))).thenReturn(result);
        assertThat(service.leads(false,3,20," 50%_! ","REJECTED")).isEqualTo(result);
        verify(references).access("crm:lead:read");
        verify(references,never()).access("crm:lead:review");
    }
    @Test void reviewListRequiresReviewPermissionAndAcceptsOnlyPendingFilter() {
        authorize("crm:lead:review");
        service.leads(true,1,100,null,"PENDING_REVIEW");
        verify(store).leads(argThat(s->"crm:lead:review".equals(s.access().action())),eq(true),eq(1),eq(100),isNull(),eq("PENDING_REVIEW"));
        when(references.access("crm:lead:review")).thenThrow(new AuthorizationDeniedException("crm:lead:review"));
        clearInvocations(store);
        assertThatThrownBy(()->service.leads(true,1,20,null,null)).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store);
    }
}
