package com.rigour.hr.application.port.out;

import com.rigour.hr.api.v1.model.HrExpenseCommand;

public interface HrExpenseCostSource {
    void verify(HrExpenseCommand expense);
}
