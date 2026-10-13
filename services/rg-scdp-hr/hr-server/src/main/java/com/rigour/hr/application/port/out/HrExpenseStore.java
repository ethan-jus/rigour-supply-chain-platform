package com.rigour.hr.application.port.out;

import com.rigour.hr.api.v1.model.HrExpenseCommand;
import com.rigour.hr.api.v1.model.HrExpenseDetailView;
import com.rigour.hr.api.v1.model.HrExpenseQuery;
import com.rigour.hr.api.v1.model.HrPageView;
import com.rigour.hr.api.v1.model.PayrollActionCommand;
import com.rigour.hr.api.v1.model.PayrollEmployeeView;

public interface HrExpenseStore {
    HrPageView<HrExpenseDetailView> expenses(String tenant, HrExpenseQuery query, String action);

    HrExpenseDetailView expenseDetail(String tenant, String id, String action);

    HrExpenseDetailView save(
            String tenant,
            String id,
            HrExpenseCommand command,
            PayrollEmployeeView employee,
            String actor);

    HrExpenseDetailView expenseTransition(
            String tenant, String id, PayrollActionCommand command, String status, String actor);
}
