package com.rigour.integration.application.service.huolala;

import static org.assertj.core.api.Assertions.*;

import com.rigour.integration.api.v1.model.HuolalaModels.Connection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;

class HuolalaOrderNormalizerTest {
    final JsonMapper json = JsonMapper.builder().build();
    final Connection c =
            new Connection(
                    "691faa79-2d5d-45c3-9868-34d079500099",
                    "1001",
                    "企业",
                    "env://HLL",
                    true,
                    "http://localhost:5100/huolala-authorized.html",
                    false,
                    15,
                    LocalDate.of(2026, 10, 1),
                    "AUTHORIZED",
                    null,
                    false,
                    1,
                    null,
                    null);

    ObjectNode list() {
        return (ObjectNode)
                json.readTree(
                        "{\"ep_id\":1001,\"order_display_id\":\"3624071813921329726\",\"user_id\":51,\"order_status\":2,\"order_time\":\"2026-11-08"
                            + " 14:36:58\",\"coupon_fen\":500,\"price_fen\":43913}");
    }

    ObjectNode detail() {
        return (ObjectNode)
                json.readTree(
"""
{"order_detail_item":{"order_item":{"ep_id":1001,"order_display_id":"3624071813921329726","order_status":2,"order_time":"2026-11-08 14:36:58","price_fen":43913,"paid_amount":43913,"unpaid_amount":0,"refund_amount":0,
"addr_info_arr":[{"name":"发货点","addr":"发货地址","contact_name":"甲"},{"name":"收货点一","addr":"地址一"},{"name":"收货点二","addr":"地址二"}],
"order_price_arr":[{"type":203,"bill_name":"专享价","price_fen":27518},{"type":23,"bill_name":"搬运费","price_fen":15500},{"type":205,"bill_name":"税费","price_fen":1395},{"type":3,"bill_name":"优惠券","price_fen":-500}]}}}
""");
    }

    @Test
    void finalBillIncludesFeesAndDiscountOnceAndRetainsEveryStop() {
        var order = HuolalaOrderNormalizer.normalize(c, list(), detail(), "raw", "EMP001", "员工甲");
        assertThat(order.facts().billAmount()).isEqualByComparingTo("439.13");
        assertThat(order.facts().couponAmount()).isEqualByComparingTo("5.00");
        assertThat(order.facts().billState()).isEqualTo("FINAL");
        assertThat(order.facts().fees()).hasSize(4);
        assertThat(order.facts().stops()).hasSize(3);
        assertThat(order.orderNo()).isEqualTo("3624071813921329726");
        assertThat(order.raw().get("收货人信息")).contains("收货点一", "收货点二");
    }

    @Test
    void completedMonthlyBillIsCostEvenWhenUnpaidAndIgnoresDeprecatedPaymentFlag() {
        var d = detail();
        ((ObjectNode) d.at("/order_detail_item/order_item"))
                .put("paid_amount", 0)
                .put("unpaid_amount", 43913)
                .put("pay_status", 2);
        var order = HuolalaOrderNormalizer.normalize(c, list(), d, "raw", null, null);
        assertThat(order.paidAmount()).isZero();
        assertThat(order.facts().billAmount()).isEqualByComparingTo("439.13");
        assertThat(order.facts().billState()).isEqualTo("FINAL");
    }

    @ParameterizedTest
    @CsvSource({"0,匹配中", "6,订单未支付", "7,运送中", "17,支付失败取消", "18,支付超时取消", "99,未知状态（99）"})
    void keepsPlatformOrderStatusDistinctFromPaymentProgress(int code, String label) {
        var d = detail();
        ((ObjectNode) d.at("/order_detail_item/order_item")).put("order_status", code);
        var order = HuolalaOrderNormalizer.normalize(c, list(), d, "raw", null, null);
        assertThat(order.status()).isEqualTo(label);
        assertThat(order.raw()).containsEntry("平台订单状态码", String.valueOf(code));
        assertThat(order.paidAmount()).isEqualByComparingTo("439.13");
    }

    @Test
    void refundAndCancellationNeverInventANetAmount() {
        var d = detail();
        ((ObjectNode) d.at("/order_detail_item/order_item"))
                .put("order_status", 3)
                .put("price_fen", 5000)
                .put("paid_amount", 0)
                .put("refund_amount", 5000);
        var order = HuolalaOrderNormalizer.normalize(c, list(), d, "raw", null, null);
        assertThat(order.status()).isEqualTo("已取消");
        assertThat(order.facts().billState()).isEqualTo("REVIEW");
        assertThat(order.facts().billAmount()).isEqualByComparingTo("50.00");
        assertThat(order.facts().refundAmount()).isEqualByComparingTo("50.00");
    }

    @Test
    void missingRefundOrDetailPreservesUnknownAndRequiresReview() {
        var d = detail();
        ((ObjectNode) d.at("/order_detail_item/order_item")).remove("refund_amount");
        var order = HuolalaOrderNormalizer.normalize(c, list(), d, "raw", null, null);
        assertThat(order.facts().refundAmount()).isNull();
        assertThat(order.facts().billState()).isEqualTo("REVIEW");
        assertThat(
                        HuolalaOrderNormalizer.normalize(
                                        c, list(), json.createObjectNode(), "raw", null, null)
                                .facts()
                                .billState())
                .isEqualTo("REVIEW");
    }

    @Test
    void wrongEnterpriseAndFractionalCentsCannotPass() {
        var d = detail();
        ((ObjectNode) d.at("/order_detail_item/order_item")).put("ep_id", 9999);
        assertThatThrownBy(() -> HuolalaOrderNormalizer.normalize(c, list(), d, "raw", null, null))
                .hasMessageContaining("企业");
        ((ObjectNode) d.at("/order_detail_item/order_item"))
                .put("ep_id", 1001)
                .put("price_fen", 4.5);
        assertThatThrownBy(() -> HuolalaOrderNormalizer.normalize(c, list(), d, "raw", null, null))
                .hasMessageContaining("整数分");
    }

    @Test
    void paymentProgressIsNotCostDifferenceButChangedBillIs() {
        var d = detail();
        var first = HuolalaOrderNormalizer.normalize(c, list(), d, "old", null, null);
        ((ObjectNode) d.at("/order_detail_item/order_item"))
                .put("paid_amount", 0)
                .put("unpaid_amount", 43913);
        var paid = HuolalaOrderNormalizer.normalize(c, list(), d, "new", null, null);
        assertThat(HuolalaOrderNormalizer.fingerprint(first, json))
                .isEqualTo(HuolalaOrderNormalizer.fingerprint(paid, json));
        ((ObjectNode) d.at("/order_detail_item/order_item")).put("price_fen", 45000);
        assertThat(HuolalaOrderNormalizer.fingerprint(first, json))
                .isNotEqualTo(
                        HuolalaOrderNormalizer.fingerprint(
                                HuolalaOrderNormalizer.normalize(c, list(), d, "new", null, null),
                                json));
    }

    @Test
    void vouchersKeepFeeRowsSeparateAndNeverExposeSourceUrlsOrAlterCostFingerprint() {
        var d = detail();
        var item = (ObjectNode) d.at("/order_detail_item/order_item");
        item.put("price_fen", 13764).put("paid_amount", 13764);
        var fee = (ObjectNode) item.path("order_price_arr").get(1);
        fee.putArray("img_urls").add("https://luna-cdn.huolala.cn/a.png?signature=secret");
        item.putArray("receipt_url").add("https://luna-cdn.huolala.cn/receipt.jpg");
        item.putArray("img_urls").add("https://luna-cdn.huolala.cn/bill.png");
        d.putObject("driver_info")
                .put("name", "王师傅")
                .put("phone_no", "隐私号码")
                .put("license_plate", "鄂A**123");
        var order = HuolalaOrderNormalizer.normalize(c, list(), d, "raw", null, null);
        assertThat(order.facts().billAmount()).isEqualByComparingTo("137.64");
        assertThat(order.facts().fees()).hasSize(4);
        assertThat(order.facts().receipts()).hasSize(1);
        assertThat(order.facts().billVouchers()).hasSize(1);
        assertThat(order.raw().get("司机")).isEqualTo("王师傅");
        assertThat(json.writeValueAsString(order)).doesNotContain("https://", "secret");
        var before = HuolalaOrderNormalizer.fingerprint(order, json);
        fee.putArray("img_urls").add("https://luna-cdn.huolala.cn/a.png?signature=rotated");
        var renewed = HuolalaOrderNormalizer.normalize(c, list(), d, "raw2", null, null);
        assertThat(renewed.facts().fees().get(1).vouchers().getFirst().id())
                .isEqualTo(order.facts().fees().get(1).vouchers().getFirst().id());
        assertThat(HuolalaOrderNormalizer.fingerprint(renewed, json)).isEqualTo(before);
    }

    @Test
    void nullOrMalformedArraysAreMissingNotAnExplicitInstructionToClearEvidence() {
        var d = detail();
        var item = (ObjectNode) d.at("/order_detail_item/order_item");
        item.putNull("order_price_arr")
                .put("receipt_url", "temporarily unavailable")
                .putObject("img_urls");
        var missing = HuolalaOrderNormalizer.normalize(c, list(), d, "raw", null, null);
        assertThat(missing.raw())
                .containsEntry("费用明细已提供", "false")
                .containsEntry("回单已提供", "false")
                .containsEntry("账单凭证已提供", "false");
        item.putArray("order_price_arr");
        item.putArray("receipt_url");
        item.putArray("img_urls");
        var empty = HuolalaOrderNormalizer.normalize(c, list(), d, "raw", null, null);
        assertThat(empty.raw())
                .containsEntry("费用明细已提供", "true")
                .containsEntry("回单已提供", "true")
                .containsEntry("账单凭证已提供", "true");
    }
}
