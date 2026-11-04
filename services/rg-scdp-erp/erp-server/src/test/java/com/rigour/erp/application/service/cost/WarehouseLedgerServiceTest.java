package com.rigour.erp.application.service.cost;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.erp.api.v1.model.*;
import com.rigour.erp.application.port.out.ErpProductDictionary;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import tools.jackson.databind.json.JsonMapper;

class WarehouseLedgerServiceTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void missingWarehouseCityIsReportedAsAnArchiveProblem(String city) {
        var references = mock(CostLedgerReferenceService.class);
        var dictionary = mock(ErpProductDictionary.class);
        var service = new WarehouseLedgerService(references, dictionary);
        var action = "erp:cost:rental:write";
        when(references.warehouse("WH1", action)).thenReturn(new CostLedgerPartyView(
                "1", "WH1", "武汉仓", city, null, null, null, "WAREHOUSE"));
        var command = JsonMapper.builder().build().readValue("""
                {"cityCode":"WH","cityName":"武汉市","costDate":"2026-11-04",
                 "paymentStatusCode":"UNPAID","warehouseLedger":{
                 "schemaVersion":2,"warehouseCode":"WH1","feeItems":[{
                 "feeKey":"rent","feeTypeCode":"RENT","feeAmount":60000,
                 "allocationMethodCode":"MONTH_EQUAL","serviceFrom":"2026-11-01",
                 "serviceTo":"2027-04-30"}]}}
                """, ErpCostCommand.class);
        assertThatThrownBy(() -> service.normalize(command, action, false))
                .isInstanceOf(com.rigour.shared.core.exception.BusinessException.class)
                .hasMessageContaining("仓库档案未配置归属城市");
        verifyNoInteractions(dictionary);
    }
}
