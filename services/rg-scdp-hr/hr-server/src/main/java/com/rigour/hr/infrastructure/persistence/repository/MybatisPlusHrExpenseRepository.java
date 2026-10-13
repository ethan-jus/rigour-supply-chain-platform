package com.rigour.hr.infrastructure.persistence.repository;

import static com.rigour.hr.infrastructure.persistence.repository.HrPayrollPersistenceSupport.*;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rigour.hr.api.v1.model.HrExpenseCommand;
import com.rigour.hr.api.v1.model.HrExpenseDetailView;
import com.rigour.hr.api.v1.model.HrExpenseQuery;
import com.rigour.hr.api.v1.model.HrPageView;
import com.rigour.hr.api.v1.model.PayrollActionCommand;
import com.rigour.hr.api.v1.model.PayrollEmployeeView;
import com.rigour.hr.application.port.out.HrExpenseStore;
import com.rigour.hr.infrastructure.persistence.entity.HrExpenseEntity;
import com.rigour.hr.infrastructure.persistence.mapper.HrExpenseMapper;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import com.rigour.shared.core.code.BusinessCodeRule;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** 报销独立持久化，采用与成本一致的草稿、确认和作废流程。 */
@Repository
public class MybatisPlusHrExpenseRepository implements HrExpenseStore {
    private final HrExpenseMapper mapper;
    private final HrPayrollPersistenceSupport support;
    private final ObjectMapper json;
    private final HrDataScope scope;

    public MybatisPlusHrExpenseRepository(
            HrExpenseMapper mapper,
            HrPayrollPersistenceSupport support,
            ObjectMapper json,
            HrDataScope scope) {
        this.mapper = mapper;
        this.support = support;
        this.json = json;
        this.scope = scope;
    }

    private QueryWrapper<HrExpenseEntity> query(String tenant, String action) {
        return scope.apply(new QueryWrapper<HrExpenseEntity>().eq("tenant_id", tenant), action);
    }

    @Override
    public HrPageView<HrExpenseDetailView> expenses(
            String tenant, HrExpenseQuery c, String action) {
        var q =
                query(tenant, action)
                        .eq(text(c.typeCode()), "type_code", c.typeCode())
                        .eq(text(c.employeeCode()), "employee_code", c.employeeCode())
                        .eq(text(c.cityCode()), "city_code", c.cityCode())
                        .eq(text(c.statusCode()), "status_code", c.statusCode())
                        .eq(
                                text(c.paymentStatusCode()),
                                "payment_status_code",
                                c.paymentStatusCode())
                        .ge(c.dateFrom() != null, "cost_date", c.dateFrom())
                        .le(c.dateTo() != null, "cost_date", c.dateTo());
        if (text(c.keyword()))
            q.and(
                    w ->
                            w.like("document_no", c.keyword())
                                    .or()
                                    .like("title", c.keyword())
                                    .or()
                                    .like("employee_name", c.keyword()));
        long total = mapper.selectCount(q);
        int begin = Math.max(0, c.begin()), step = pageSize(c.step());
        return new HrPageView<>(
                total,
                begin,
                step,
                mapper
                        .selectList(
                                q.orderByDesc("cost_date", "id")
                                        .last("LIMIT " + step + " OFFSET " + begin))
                        .stream()
                        .map(this::view)
                        .toList());
    }

    @Override
    public HrExpenseDetailView expenseDetail(String tenant, String id, String action) {
        var row = mapper.selectOne(query(tenant, action).eq("id", id));
        if (row == null) throw error(ErrorCode.NOT_FOUND, "报销不存在或无权限");
        return view(row);
    }

    @Override
    @Transactional
    public HrExpenseDetailView save(
            String tenant, String id, HrExpenseCommand c, PayrollEmployeeView e, String actor) {
        support.lock(tenant);
        String body = json.writeValueAsString(c);
        if (id == null) {
            var previous =
                    mapper.selectOne(
                            new QueryWrapper<HrExpenseEntity>()
                                    .eq("tenant_id", tenant)
                                    .eq("request_id", c.requestId()));
            if (previous != null) {
                var accessible = expenseDetail(tenant, previous.getId(), "hr:expense:write");
                if (!json.writeValueAsString(accessible.data()).equals(body))
                    throw error(ErrorCode.CONFLICT, "请求编号已使用");
                return accessible;
            }
            var row = new HrExpenseEntity();
            row.setId(UUID.randomUUID().toString());
            row.setTenantId(tenant);
            row.setRequestId(c.requestId());
            row.setDocumentNo(
                    new BusinessCodeGenerator().generate(BusinessCodeRule.millisecond("EXP", 6)));
            row.setTitle(c.title());
            row.setTypeCode(c.typeCode());
            row.setEmployeeCode(c.employeeCode());
            row.setEmployeeName(e.employeeName());
            row.setDepartmentId(e.departmentId());
            row.setDepartmentName(e.departmentName());
            row.setCityCode(c.cityCode());
            row.setCostDate(c.costDate());
            row.setAmount(c.amount());
            row.setPaymentStatusCode(c.paymentStatusCode());
            row.setDataJson(body);
            row.setCreatedBy(actor);
            row.setUpdatedBy(actor);
            mapper.insert(row);
            id = row.getId();
        } else {
            var old = expenseDetail(tenant, id, "hr:expense:write");
            if ("VOID".equals(old.statusCode())) throw error(ErrorCode.CONFLICT, "已作废报销不能修改");
            if ("CONFIRMED".equals(old.statusCode()) && !text(c.reason()))
                throw error(ErrorCode.BAD_REQUEST, "修改已确认报销需填写原因");
            int changed =
                    mapper.update(
                            null,
                            new UpdateWrapper<HrExpenseEntity>()
                                    .eq("tenant_id", tenant)
                                    .eq("id", id)
                                    .eq("revision", c.revision())
                                    .ne("status_code", "VOID")
                                    .set("title", c.title())
                                    .set("type_code", c.typeCode())
                                    .set("employee_code", c.employeeCode())
                                    .set("employee_name", e.employeeName())
                                    .set("department_id", e.departmentId())
                                    .set("department_name", e.departmentName())
                                    .set("city_code", c.cityCode())
                                    .set("cost_date", c.costDate())
                                    .set("amount", c.amount())
                                    .set("payment_status_code", c.paymentStatusCode())
                                    .set("data_json", body)
                                    .set("status_code", "DRAFT")
                                    .setSql("revision=revision+1")
                                    .set("updated_by", actor)
                                    .set("updated_time", LocalDateTime.now(ZoneOffset.UTC)));
            if (changed != 1) throw error(ErrorCode.CONFLICT, "报销已修改，请刷新");
            support.audit(tenant, id, "EXPENSE_BEFORE", actor, c.reason(), old);
        }
        var saved = expenseDetail(tenant, id, "hr:expense:write");
        support.audit(tenant, id, "EXPENSE_SAVE", actor, c.reason(), saved);
        return saved;
    }

    @Override
    @Transactional
    public HrExpenseDetailView expenseTransition(
            String tenant, String id, PayrollActionCommand c, String status, String actor) {
        support.lock(tenant);
        String action = "hr:expense:" + ("VOID".equals(status) ? "void" : "confirm");
        var old = expenseDetail(tenant, id, action);
        if ("VOID".equals(old.statusCode())
                || "CONFIRMED".equals(status) && !"DRAFT".equals(old.statusCode())
                || old.revision() != c.revision()) throw error(ErrorCode.CONFLICT, "报销状态或版本已变更");
        if (("VOID".equals(status) || old.data().amount().signum() == 0) && !text(c.reason()))
            throw error(ErrorCode.BAD_REQUEST, "请填写作废或零元确认原因");
        if (mapper.update(
                        null,
                        new UpdateWrapper<HrExpenseEntity>()
                                .eq("tenant_id", tenant)
                                .eq("id", id)
                                .eq("revision", c.revision())
                                .eq("status_code", old.statusCode())
                                .set("status_code", status)
                                .setSql("revision=revision+1")
                                .set("updated_by", actor)
                                .set("updated_time", LocalDateTime.now(ZoneOffset.UTC)))
                != 1) throw error(ErrorCode.CONFLICT, "报销状态或版本已变更");
        var saved = expenseDetail(tenant, id, action);
        support.audit(tenant, id, "EXPENSE_" + status, actor, c.reason(), saved);
        return saved;
    }

    private HrExpenseDetailView view(HrExpenseEntity r) {
        return new HrExpenseDetailView(
                r.getId(),
                r.getDocumentNo(),
                r.getStatusCode(),
                r.getRevision(),
                json.readValue(r.getDataJson(), HrExpenseCommand.class),
                r.getEmployeeName(),
                r.getDepartmentId(),
                r.getDepartmentName(),
                r.getUpdatedTime().toInstant(ZoneOffset.UTC));
    }
}
