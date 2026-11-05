package com.rigour.merchant.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.rigour.merchant.api.v1.model.StoreModels.*;
import com.rigour.merchant.application.port.out.*;
import com.rigour.settings.api.v1.model.AdminDivisionCatalog;
import com.rigour.tenant.iam.api.v1.model.StoreAccessView;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.exception.RequestValidationException;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;

class StoreIntakeServiceTest {
    final StoreReferenceClient references=mock(StoreReferenceClient.class);
    final StorePoolStore store=mock(StorePoolStore.class);
    final StorePoiClient pois=mock(StorePoiClient.class);
    final StorePoolService service=new StorePoolService(references,mock(CrmEmployeeClient.class),store,pois);
    final Poi poi=new Poi("B001","核实球馆","湖墅南路1号",null,"080113","330105",new BigDecimal("120.15"),new BigDecimal("30.28"));
    final AdminDivisionCatalog catalog=new AdminDivisionCatalog("V","test","test",List.of(
            new AdminDivisionCatalog.Node("330000","浙江省","PROVINCE",null,"EAST"),
            new AdminDivisionCatalog.Node("330100","杭州市","PREFECTURE","330000",null),
            new AdminDivisionCatalog.Node("330105","拱墅区","COUNTY","330100",null),
            new AdminDivisionCatalog.Node("330200","宁波市","PREFECTURE","330000",null),
            new AdminDivisionCatalog.Node("330203","海曙区","COUNTY","330200",null),
            new AdminDivisionCatalog.Node("620000","甘肃省","PROVINCE",null,"NORTHWEST"),
            new AdminDivisionCatalog.Node("620100","兰州市","PREFECTURE","620000",null),
            new AdminDivisionCatalog.Node("620102","城关区","COUNTY","620100",null)));
    StoreAccessView access(String action,String employee) {
        return new StoreAccessView("T","U",action,1,employee,employee==null?null:"E1","操作人","10",Set.of(action),
                List.of(new StoreAccessView.Grant("ALL",List.of(),false,false,List.of(new StoreAccessView.Area("V","330100")))),false,List.of());
    }
    @BeforeEach void setup() {
        when(references.access("crm:lead:create")).thenReturn(access("crm:lead:create",null));
        when(references.divisions()).thenReturn(catalog);
        when(references.storeTypeCodes("T")).thenReturn(Set.of("POI_080113"));
        when(references.businessStatusCodes("T")).thenReturn(Set.of("UNKNOWN","OPEN","TEMP_CLOSED","CLOSED"));
    }
    LeadCommand manual(String area,String type,String coordinateSystem) {
        return new LeadCommand(UUID.randomUUID().toString(),"手动球馆","实地核实地址",area,poi.longitude(),poi.latitude(),null,type,coordinateSystem);
    }
    @Test void adminWithoutEmployeeCanCreateButCannotClaim() {
        var c=manual("330105","POI_080113","WGS84");
        service.submit(c);
        verify(store).submit(any(),eq(c));
        when(references.access("crm:pool:claim")).thenReturn(access("crm:pool:claim",null));
        assertThatThrownBy(()->service.claim("123",new Claim(UUID.randomUUID().toString(),1))).isInstanceOf(RequestValidationException.class).hasMessageContaining("员工");
        verify(store,never()).claim(any(),any(),any());
        verifyNoInteractions(pois);
    }
    @Test void providerDetailIsVerifiedAndItsCanonicalFieldsAreUsed() {
        var c=new AmapAdmission(UUID.randomUUID().toString(),poi.id());
        when(pois.detail("T",poi.id())).thenReturn(poi);
        when(store.admit(any(),eq(c),eq(poi),eq("POI_080113"))).thenReturn(new Admission("123",poi.name()));
        assertThat(service.admit(c)).isEqualTo(new Admission("123",poi.name()));
        verify(store).admit(any(),eq(c),eq(poi),eq("POI_080113"));
        verify(store,never()).claim(any(),any(),any());
        verify(store,never()).submit(any(),any());
    }
    @Test void providerFailureOrMissingFieldsCannotBeAdmitted() {
        var c=new AmapAdmission(UUID.randomUUID().toString(),"B001");
        when(pois.detail("T","B001")).thenReturn(null);
        assertThatThrownBy(()->service.admit(c)).isInstanceOf(RequestValidationException.class);
        when(pois.detail("T","B001")).thenReturn(new Poi("B001",poi.name(),null,null,"080113","330105",poi.longitude(),poi.latitude()));
        assertThatThrownBy(()->service.admit(c)).isInstanceOf(RequestValidationException.class).hasMessageContaining("地址");
        when(pois.detail("T","B001")).thenThrow(new IllegalStateException("provider unavailable"));
        assertThatThrownBy(()->service.admit(c)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(store);
    }
    @Test void mismatchedPoiOrUnauthorizedCountyCannotBeAdmitted() {
        var c=new AmapAdmission(UUID.randomUUID().toString(),"B001");
        when(pois.detail("T","B001")).thenReturn(new Poi("B002",poi.name(),poi.address(),null,"080113","330105",poi.longitude(),poi.latitude()));
        assertThatThrownBy(()->service.admit(c)).isInstanceOf(RequestValidationException.class);
        when(pois.detail("T","B001")).thenReturn(new Poi("B001",poi.name(),poi.address(),null,"080113","620102",poi.longitude(),poi.latitude()));
        assertThatThrownBy(()->service.admit(c)).isInstanceOf(RequestValidationException.class);
        verifyNoInteractions(store);
    }
    @Test void unmappedOrDisabledClassificationRequiresManualReview() {
        when(pois.detail("T","B001")).thenReturn(poi);
        when(references.storeTypeCodes("T")).thenReturn(Set.of());
        assertThatThrownBy(()->service.admit(new AmapAdmission(UUID.randomUUID().toString(),"B001")))
                .isInstanceOf(RequestValidationException.class).hasMessageContaining("分类");
        verifyNoInteractions(store);
    }
    @Test void citySearchExcludesOtherCitiesAndInvalidPositions() {
        when(pois.search("T","球馆","330100")).thenReturn(List.of(poi,
                new Poi("B002","宁波店","地址",null,"080113","330203",poi.longitude(),poi.latitude()),
                new Poi("B003","坏位置","地址",null,"080113","330105",new BigDecimal("181"),poi.latitude())));
        assertThat(service.intakeCandidates(" 球馆 ","330100")).containsExactly(poi);
        assertThatThrownBy(()->service.intakeCandidates("球馆","620100")).isInstanceOf(AuthorizationDeniedException.class);
        verify(pois,never()).search("T","球馆","620100");
    }
    @Test void manualSubmissionValidatesAreaClassificationAndCoordinateSystem() {
        assertThatThrownBy(()->service.submit(manual("620102","POI_080113","GCJ02"))).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(()->service.submit(manual("330100","POI_080113","GCJ02"))).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(()->service.submit(manual("330105","UNKNOWN_TYPE","GCJ02"))).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(()->service.submit(manual("330105","POI_080113","BD09"))).isInstanceOf(RequestValidationException.class);
        verifyNoInteractions(store,pois);
    }
    @Test void readOnlyLibraryPermissionCannotCreateOrChangeStatus() {
        when(references.access("crm:lead:create")).thenThrow(new AuthorizationDeniedException("crm:lead:create"));
        assertThatThrownBy(()->service.intakeContext()).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(()->service.lookup("球馆","330105")).isInstanceOf(AuthorizationDeniedException.class);
        assertThatThrownBy(()->service.admit(new AmapAdmission(UUID.randomUUID().toString(),"B001"))).isInstanceOf(AuthorizationDeniedException.class);
        when(references.access("crm:store:manage")).thenThrow(new AuthorizationDeniedException("crm:store:manage"));
        assertThatThrownBy(()->service.businessStatus("123",new BusinessStatusChange(UUID.randomUUID().toString(),1,"CLOSED","现场已撤店",true))).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store,pois);
    }
    @Test void managementFlagRequiresManagementPermissionAndStateMustBeEnabled() {
        var owner=access("crm:store:status-write","1");
        when(references.access("crm:store:status-write")).thenReturn(owner);
        var c=new BusinessStatusChange(UUID.randomUUID().toString(),1,"TEMP_CLOSED","门头装修暂停营业",false);
        service.businessStatus("123",c);
        verify(store).businessStatus(any(),eq("123"),eq(c));
        clearInvocations(store);
        when(references.businessStatusCodes("T")).thenReturn(Set.of("OPEN"));
        assertThatThrownBy(()->service.businessStatus("123",c)).isInstanceOf(RequestValidationException.class).hasMessageContaining("字典");
        when(references.access("crm:store:manage")).thenThrow(new AuthorizationDeniedException("crm:store:manage"));
        assertThatThrownBy(()->service.businessStatus("123",new BusinessStatusChange(c.requestId(),1,"OPEN","恢复营业",true))).isInstanceOf(AuthorizationDeniedException.class);
        verifyNoInteractions(store);
    }
    @Test void reviewerCanFindExistingArchivesWithoutReceivingCreatePermission() {
        when(references.access("crm:lead:review")).thenReturn(access("crm:lead:review","1"));
        when(references.access("crm:lead:create")).thenThrow(new AuthorizationDeniedException("crm:lead:create"));
        service.reviewLookup(" 球馆 ","330105");
        verify(store).lookup(argThat(scope->"crm:lead:review".equals(scope.access().action())),eq("球馆"),eq("330105"));
        verify(references,never()).access("crm:lead:create");
        verifyNoInteractions(pois);
    }

}
