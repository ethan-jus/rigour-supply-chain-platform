package com.rigour.erp.application.port.out;

import java.util.Set;

/**
 * 商品业务输入要核验的业务字典项。
 *
 * <p>字典是商品单位和状态的唯一事实来源：停用项、字典不可达都不能自动放行新输入，
 * 也不能把未知编码当成合法值写进商品表。来源历史事实不走这里，保留原始编码。</p>
 */
public interface ErpProductDictionary {
    /** 指定字典在租户下当前启用的字典项编码；字典编码不合法时抛出异常。 */
    Set<String> validCodes(String tenant, String dictionaryCode);
}
