package com.rigour.order.application.port.out;
import com.rigour.erp.api.v1.model.ProcurementCostSource;
public interface ProcurementFreightSource { ProcurementCostSource source(String tenant,String code); }
