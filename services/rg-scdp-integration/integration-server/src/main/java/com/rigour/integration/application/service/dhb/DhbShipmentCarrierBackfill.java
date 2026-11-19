package com.rigour.integration.application.service.dhb;

import com.rigour.integration.application.port.out.DictionarySourceMappingStore;
import com.rigour.integration.application.port.out.OrderSalesOrderProjectionClient;
import com.rigour.order.api.v1.model.ShipmentTransportModels.CarrierBackfill;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 按已解析的来源字典回填现有运输，独立于发货履约重试。 */
@Component
public class DhbShipmentCarrierBackfill {
    private static final Logger log = LoggerFactory.getLogger(DhbShipmentCarrierBackfill.class);
    private static final int PAGE_SIZE = 20;
    private final DictionarySourceMappingStore mappings;
    private final OrderSalesOrderProjectionClient order;
    private final TransactionTemplate transaction;
    private long cursor;

    public DhbShipmentCarrierBackfill(
            DictionarySourceMappingStore mappings, OrderSalesOrderProjectionClient order,
            PlatformTransactionManager transactions) {
        this.mappings = mappings;
        this.order = order;
        transaction = new TransactionTemplate(transactions);
    }

    @Scheduled(fixedDelayString = "${rigour.integration.dhb.carrier-backfill-poll-ms:30000}",
            initialDelay = 30000)
    public void backfill() {
        var batch = mappings.pendingShipmentCarrierBackfills(cursor, PAGE_SIZE);
        for (var entry : batch) {
            var mapping = entry.mapping();
            try {
                transaction.executeWithoutResult(status -> {
                    String tenant = entry.tenant();
                    if (!mappings.lockShipmentCarrierBackfill(tenant, mapping.id(), mapping.revision()))
                        return;
                    int count = order.backfillShipmentCarrier(
                            DhbOrderSyncService.orderServiceCaller(UUID.fromString(tenant)),
                            new CarrierBackfill(mapping.sourceValue(), mapping.targetItemCode()));
                    if (count < CarrierBackfill.BATCH_SIZE)
                        mappings.completeShipmentCarrierBackfill(tenant, mapping.id(), mapping.revision());
                });
            } catch (RuntimeException error) {
                log.warn("订货宝物流公司回填待重试 mappingId={} errorType={}",
                        mapping.id(), error.getClass().getSimpleName());
            }
            cursor = mapping.id();
        }
        // 下轮继续处理未跑完的映射以及刚更新的版本。
        if (batch.size() < PAGE_SIZE) cursor = 0;
    }
}
