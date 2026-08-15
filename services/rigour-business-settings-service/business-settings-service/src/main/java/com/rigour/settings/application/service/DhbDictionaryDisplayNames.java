package com.rigour.settings.application.service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 订货宝官方有限枚举的显示名称目录。
 *
 * <p>这里只登记订货宝接口文档已明确说明、或接口同时返回编码和名称的有限枚举。
 * 未登记值不会按英文、数字或字段名猜测含义，必须等待订货宝返回明确名称或后续补充官方依据。</p>
 */
final class DhbDictionaryDisplayNames {
    private static final Map<String, Map<String, String>> NAMES = definitions();

    private DhbDictionaryDisplayNames() { }

    /** 按模块、字典和订货宝原值精确解析；兼容官方文档中仅大小写不同的英文枚举。 */
    static String resolve(String moduleCode, String dictCode, String sourceValue) {
        if (sourceValue == null) return null;
        Map<String, String> dictionary = NAMES.get(key(moduleCode, dictCode));
        if (dictionary == null) return null;
        String exact = dictionary.get(sourceValue);
        return exact != null ? exact : dictionary.get(sourceValue.toUpperCase(Locale.ROOT));
    }

    private static Map<String, Map<String, String>> definitions() {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();

        // getGoodsList / getStockInfo / getWarehousingList。
        put(result, "ERP", "DHB_PRODUCT_STATUS",
                "T", "正常", "F", "回收站", "A", "待审", "N", "未通过", "R", "撤销");
        put(result, "ERP", "DHB_PRODUCT_PUTAWAY", "T", "上架", "F", "下架");
        put(result, "ERP", "DHB_WAREHOUSE_STATUS", "T", "正常", "F", "停用");
        put(result, "ERP", "DHB_WAREHOUSING_STATUS", "THROUGED", "已审核");
        put(result, "ERP", "DHB_WAREHOUSING_TYPE",
                "-1", "退货入库", "1", "采购入库", "3", "盘盈入库", "8", "调拨入库",
                "20", "运营入库", "21", "红冲销售出库", "7", "其他入库", "9", "联营入库");
        put(result, "ERP", "DHB_PURCHASE_ORDER_STATUS",
                "PENDING", "待审核", "WH_UP", "待入库", "WH_HALF", "部分入库",
                "CANCELLED", "已取消", "FINISHED", "已完成");
        put(result, "ERP", "DHB_PURCHASE_PAYMENT_STATUS",
                "OBLIG", "待付款", "UNCOLLECT", "部分付款", "PAIDED", "已付款", "CANCELLED", "已取消");
        put(result, "ERP", "DHB_PURCHASE_RETURN_STATUS",
                "STOCK_UP", "待出库", "CANCELLED", "已取消", "REFUNDS", "待退款", "FINISHED", "已完成");

        // getDealersList / getStaffList。
        put(result, "CRM", "DHB_CUSTOMER_STATUS",
                "T", "正常", "F", "停用", "A", "待激活", "C", "待审核");
        put(result, "CRM", "DHB_CUSTOMER_CLEARING_FORM",
                "PREPAID", "预付", "FORWARD", "现付", "POSTPAID", "后付");
        put(result, "CRM", "DHB_STAFF_STATUS", "T", "启用", "F", "停用");
        put(result, "CRM", "DHB_STAFF_TYPE",
                "SALESMAN", "业务员", "BOSS", "老板", "INDOORWORK", "内勤", "DRIVER", "司机");

        // getOrderList / getOrderContent。
        put(result, "ORDER", "DHB_ORDER_STATUS",
                "PRICING", "待核价", "PENDING", "待审核", "STOCK_UP", "待出库", "STOCKUP", "待出库",
                "SHIPPED", "待发货", "RECEIVED", "待收货", "FINISHED", "已完成",
                "FORCEDONE", "强制完成", "CANCELLED", "已取消");
        put(result, "ORDER", "DHB_ORDER_PAYMENT_STATUS",
                "OBLIG", "待收款", "UNCOLLECT", "部分收款", "PAIDED", "已收款", "CANCELLED", "已取消",
                "WAIT", "待确认", "PART", "部分确认", "UNOBLIG", "待确认付款");
        put(result, "ORDER", "DHB_ORDER_TYPE",
                "NORMAL", "普通订单", "C", "经销商提交", "M", "管理端代提交", "S", "业务员代提交");
        put(result, "ORDER", "DHB_ORDER_API_STATUS", "F", "未下载", "T", "已下载");
        put(result, "ORDER", "DHB_ORDER_EXCEPTION_STATUS", "F", "正常", "T", "异常");
        put(result, "ORDER", "DHB_ORDER_ADMIN_FLAG", "T", "管理端下单", "F", "订货端下单");
        put(result, "ORDER", "DHB_SETTLEMENT_METHOD",
                "PREPAID", "预付", "FORWARD", "现付", "POSTPAID", "后付");
        put(result, "ORDER", "DHB_INVOICE_TYPE",
                "P", "普通发票", "Z", "增值税发票", "F", "无发票");
        put(result, "ORDER", "DHB_ORDER_LINE_TYPE", "C", "正常售卖", "G", "赠品");

        // getWaitShips / getShipsList / getReturnsList。
        put(result, "ORDER", "DHB_GOODS_LIST_TYPE", "BUY", "买", "GIFT", "赠");
        put(result, "ORDER", "DHB_SHIPMENT_STATUS",
                "SHIPPED", "待发货", "RECEIVEDIN", "待收货", "RECEIVED", "已收货", "CANCELLED", "已取消");
        put(result, "ORDER", "DHB_SHIPMENT_TYPE",
                "-2", "采购退货", "10", "销售出库", "11", "盘亏出库", "17", "其他出库",
                "18", "调拨出库", "19", "联营出库");
        put(result, "ORDER", "DHB_RETURN_STATUS",
                "RETURN_AUDIT", "待退货审核", "SHIPP_CUST", "待客户发货", "SHIPPED", "待收货",
                "REFUNDED", "待退款", "FINISHED", "已完成", "CANCELLED", "已取消");
        put(result, "ORDER", "DHB_RETURN_TYPE", "0", "未确认", "1", "退货退款", "2", "仅退款");

        // getReceiptsList / getPaymentList。RECEIPT、PAYMENT 是平台对两个接口的稳定归一化值。
        put(result, "ORDER", "DHB_FINANCIAL_DOCUMENT_TYPE", "RECEIPT", "收款单", "PAYMENT", "付款单");
        put(result, "ORDER", "DHB_FINANCIAL_BUSINESS_TYPE",
                "1", "普通充值", "19", "预付款充值", "13", "订单收款", "8", "期初充值",
                "2", "退货退款", "10", "退款失败回冲", "9", "退款红冲", "5", "预存款扣款");
        put(result, "ORDER", "DHB_FINANCIAL_STATUS",
                "PEND_RECEIPT", "待确认", "PEND_RECEIPTED", "已确认", "CANCELED", "已取消");
        put(result, "ORDER", "DHB_PAYMENT_METHOD",
                "ALIPAY", "支付宝支付（原生）", "QUICK", "快捷支付", "MICRO", "微信支付（原生）",
                "OFFLINE", "转账支付", "DEPOSIT", "预存款支付", "DELIVERY", "货到付款",
                "CREDIT", "赊销支付", "REBATE", "返利支付",
                "ZHONGJIN_ALIPAY", "支付宝支付", "ZHONGJIN_WECHAT", "微信支付",
                "ZHONGJIN_QUICK", "银联快捷", "ZHONGJIN_NETBANK", "网银支付",
                "APP_ADMIN_IOS_ZHONGJIN_WECHAT", "iOS移动管理端微信支付",
                "APP_ADMIN_IOS_ZHONGJIN_ALIPAY", "iOS移动管理端支付宝支付",
                "APP_ADMIN_ANDROID_ZHONGJIN_WECHAT", "Android移动管理端微信支付",
                "APP_ADMIN_ANDROID_ZHONGJIN_ALIPAY", "Android移动管理端支付宝支付",
                "ZHONGJIN_ACCOUNT_BANK_TRANSFER", "中金来账识别",
                "MYBANK_CLOUD", "支付宝云资金", "REFUND_FAIL_RECHARGE", "退款失败回充",
                "MSE", "微企付支付", "HHT", "品牌资金账户", "ALIPAY_TRANSFER", "支付宝转账",
                "WECHAT_B2B_DIRECT", "微信B2B支付", "MYBANK_BALANCE", "云资金余额支付",
                "REFUND_RED_REVERSAL", "退款红冲", "YW_PAY", "云闪付", "TP_PAY", "通企付",
                "TPPAY_UNIFIED_WX_MINIAPP", "微信支付（通企付）",
                "TPPAY_UNIFIED_ALIPAY", "支付宝支付（通企付）",
                "TPPAY_UNIFIED_ALIPAY_QR", "吱口令（通企付）",
                "TPPAY_UNIFIED_YT_PAY", "云闪付（通企付）",
                "TPPAY_UNIFIED_QUICK", "银联快捷（通企付）",
                "TPPAY_UNIFIED_WX_FRIEND", "微信好友代付（通企付）",
                "TPPAY_UNIFIED_WX_SCAN_POS", "微信扫码支付（通企付）",
                "TPPAY_UNIFIED_ALIPAY_SCAN_POS", "支付宝扫码支付（通企付）");
        return Map.copyOf(result);
    }

    private static void put(Map<String, Map<String, String>> target, String moduleCode,
                            String dictCode, String... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("字典映射必须成对声明");
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put(pairs[index], pairs[index + 1]);
        }
        target.put(key(moduleCode, dictCode), Map.copyOf(values));
    }

    private static String key(String moduleCode, String dictCode) {
        return moduleCode.toUpperCase(Locale.ROOT) + "." + dictCode.toUpperCase(Locale.ROOT);
    }
}
