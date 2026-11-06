package com.rigour.erp.application.service.inventory;

import com.rigour.erp.application.port.out.WarehouseLocationCatalog;
import com.rigour.settings.api.v1.model.AdminDivisionCatalog;
import com.rigour.shared.core.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WarehouseLocationServiceTest {
    private final WarehouseLocationCatalog source = mock(WarehouseLocationCatalog.class);
    private final WarehouseLocationService service = new WarehouseLocationService(source);
    static AdminDivisionCatalog catalog() {
        return new AdminDivisionCatalog("2026", "目录", "", List.of(
                new AdminDivisionCatalog.Node("420000", "湖北省", "PROVINCE", null, null),
                new AdminDivisionCatalog.Node("420100", "武汉市", "PREFECTURE", "420000", null),
                new AdminDivisionCatalog.Node("420102", "江岸区", "COUNTY", "420100", null)));
    }

    @Test void codeAndPublishedVersionResolveTheFullPhysicalLocation() {
        when(source.published("2026")).thenReturn(catalog());
        service.validate("420102", "2026");
        assertThat(WarehouseLocationService.display(catalog(), "420102"))
                .isEqualTo("湖北省 / 武汉市 / 江岸区");
    }
    @Test void oldClientOmissionAndExplicitClearDoNotRequireDirectoryAccess() {
        service.validate(null, null);
        service.validate("", null);
        verifyNoInteractions(source);
    }
    @Test void rejectsProvinceUnknownCountyMissingVersionAndWrongVersion() {
        when(source.published("2026")).thenReturn(catalog());
        for (String code : List.of("420000", "420199"))
            assertThatThrownBy(() -> service.validate(code, "2026"))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("区县");
        assertThatThrownBy(() -> service.validate("420102", null)).isInstanceOf(BusinessException.class);
        when(source.published("2025")).thenReturn(catalog());
        assertThatThrownBy(() -> service.validate("420102", "2025"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不完整");
    }
    @Test void neverAcceptsAnOrphanOrCyclicAdministrativePath() {
        var broken = new AdminDivisionCatalog("2026", "", "", List.of(
                new AdminDivisionCatalog.Node("420102", "江岸区", "COUNTY", "420102", null)));
        when(source.published("2026")).thenReturn(broken);
        assertThatThrownBy(() -> service.validate("420102", "2026")).hasMessageContaining("区县");
        assertThat(WarehouseLocationService.display(broken, "420102")).isEmpty();
    }
}
