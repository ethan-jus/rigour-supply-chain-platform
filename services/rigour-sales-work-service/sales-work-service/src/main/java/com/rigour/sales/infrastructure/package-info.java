/**
 * 出站适配层。持久化按dataobject/mapper/repository组织，并容纳CRM只读投影、HR/AI事件、
 * COS文件、幂等、Outbox、审计和缓存适配；不得直接访问其他领域Schema。
 */
package com.rigour.sales.infrastructure;
