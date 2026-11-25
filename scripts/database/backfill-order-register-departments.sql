-- 同一租户、员工和订单日期必须恰好落在一条主部门任职内。保留已有证据和冻结快照。
UPDATE rigour_order.order_sales_order o
JOIN rigour_hr.hr_employee_assignment a
  ON a.tenant_id=o.tenant_id AND a.employee_code=o.owner_employee_code
 AND a.effective_from<=o.order_date AND (a.effective_to IS NULL OR o.order_date<a.effective_to)
LEFT JOIN rigour_hr.hr_employee_assignment other
  ON other.tenant_id=a.tenant_id AND other.employee_code=a.employee_code AND other.id<>a.id
 AND other.effective_from<=o.order_date AND (other.effective_to IS NULL OR o.order_date<other.effective_to)
LEFT JOIN rigour_order.order_attribution_snapshot s
  ON s.tenant_id=o.tenant_id AND s.order_id=o.id AND s.state='FROZEN'
SET o.register_assignment_id=a.id,o.register_employee_code=o.owner_employee_code,
    o.register_order_date=o.order_date,o.register_department_id=a.department_id,
    o.register_department_name=a.department_name_snapshot
WHERE o.deleted=0 AND o.register_assignment_id IS NULL AND other.id IS NULL AND s.order_id IS NULL;
