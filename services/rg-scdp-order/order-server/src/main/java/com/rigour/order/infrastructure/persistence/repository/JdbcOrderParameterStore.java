package com.rigour.order.infrastructure.persistence.repository;

import com.rigour.order.api.v1.OrderParameterApi.*;
import com.rigour.order.application.port.out.OrderParameterStore;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public class JdbcOrderParameterStore implements OrderParameterStore {
    public static final String CODE = "SALES_ORDER_MAX_LINES";
    private final JdbcTemplate jdbc;

    public JdbcOrderParameterStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Parameter view(String value, long revision) {
        return new Parameter(
                CODE,
                "销售订单商品明细上限",
                "订单与售后",
                value,
                "200",
                "限制单张自研销售订单可保存的商品行数；系统最大支持200行。",
                "保存后对新建和编辑草稿生效，历史订单及来源同步保持原规则。",
                1,
                200,
                revision);
    }

    public Parameter maximumManualLines(String tenant) {
        return jdbc
                .query(
                        "SELECT parameter_value,revision FROM order_business_parameter WHERE"
                            + " tenant_id=? AND parameter_code=?",
                        (r, n) -> view(r.getString(1), r.getLong(2)),
                        tenant,
                        CODE)
                .stream()
                .findFirst()
                .orElse(view("200", 0));
    }

    @Transactional
    public Parameter saveMaximumManualLines(String tenant, String actor, Change c) {
        int value;
        try {
            value = Integer.parseInt(c.value());
        } catch (RuntimeException e) {
            throw invalid("请输入1至200的整数");
        }
        if (value < 1 || value > 200) throw invalid("明细上限必须在1至200之间");
        if (c.reason() == null || c.reason().isBlank() || c.reason().length() > 500)
            throw invalid("请填写不超过500字的变更原因");
        jdbc.update(
                "INSERT INTO"
                    + " order_business_parameter(tenant_id,parameter_code,parameter_value,revision,updated_by)"
                    + " VALUES(?,?,'200',0,?) ON DUPLICATE KEY UPDATE"
                    + " tenant_id=order_business_parameter.tenant_id",
                tenant,
                CODE,
                actor);
        var before =
                jdbc.queryForObject(
                        "SELECT parameter_value,revision FROM order_business_parameter WHERE"
                            + " tenant_id=? AND parameter_code=? FOR UPDATE",
                        (r, n) -> view(r.getString(1), r.getLong(2)),
                        tenant,
                        CODE);
        if (before.revision() != c.revision())
            throw new BusinessException(ErrorCode.CONFLICT, "参数已被修改，请刷新后重试", List.of());
        jdbc.update(
                "UPDATE order_business_parameter SET"
                    + " parameter_value=?,revision=revision+1,updated_by=? WHERE tenant_id=? AND"
                    + " parameter_code=?",
                Integer.toString(value),
                actor,
                tenant,
                CODE);
        jdbc.update(
                "INSERT INTO"
                    + " order_parameter_audit(tenant_id,actor_id,parameter_code,old_value,new_value,reason,revision)"
                    + " VALUES(?,?,?,?,?,?,?)",
                tenant,
                actor,
                CODE,
                before.value(),
                Integer.toString(value),
                c.reason().trim(),
                before.revision() + 1);
        return maximumManualLines(tenant);
    }

    private static BusinessException invalid(String m) {
        return new BusinessException(ErrorCode.BAD_REQUEST, m, List.of());
    }
}
