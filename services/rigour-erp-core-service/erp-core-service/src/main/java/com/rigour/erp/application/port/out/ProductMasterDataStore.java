package com.rigour.erp.application.port.out;

import com.rigour.erp.api.v1.model.BrandView;
import com.rigour.erp.api.v1.model.CategoryView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.erp.api.v1.model.ProductPageView;
import com.rigour.erp.api.v1.model.SkuPageView;
import com.rigour.erp.api.v1.model.SpecificationView;
import com.rigour.erp.api.v1.model.TagView;
import com.rigour.erp.application.model.DictionaryMappingAudit;
import com.rigour.erp.domain.model.product.Brand;
import com.rigour.erp.domain.model.product.Category;
import com.rigour.erp.domain.model.product.MasterDataObjectType;
import com.rigour.erp.domain.model.product.Product;
import com.rigour.erp.domain.model.product.Specification;
import com.rigour.erp.domain.model.product.Tag;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** ERP 商品主数据持久化端口；实现是 ERP Schema 的唯一写者。 */
public interface ProductMasterDataStore {

    ProductPageView products(String tenantId, int begin, int step, String query,
                             String internalStatus, String sourcePutaway);

    SkuPageView skus(String tenantId, int begin, int step, String query,
                     String internalStatus, String sourcePutaway);

    MasterDataPageView<CategoryView> categories(String tenantId, int begin, int step,
                                                String query, String status);

    MasterDataPageView<BrandView> brands(String tenantId, int begin, int step,
                                         String query, String status);

    MasterDataPageView<SpecificationView> specifications(String tenantId, int begin, int step,
                                                         String query, String status);

    MasterDataPageView<TagView> tags(String tenantId, int begin, int step,
                                     String query, String status);

    UUID startRun(String tenantId, UUID connectorId, UUID actorId,
                  MasterDataObjectType objectType, int maxPages);

    /** 定时同步使用同一批次与互斥锁模型，但在批次中记录 SCHEDULED 触发方式。 */
    default UUID startScheduledRun(String tenantId, UUID connectorId, UUID actorId,
                                   MasterDataObjectType objectType, int maxPages) {
        return startRun(tenantId, connectorId, actorId, objectType, maxPages);
    }

    ImportResult importProduct(String tenantId, UUID runId, Product product);

    ImportResult importCategory(String tenantId, UUID runId, Category category);

    ImportResult importBrand(String tenantId, UUID runId, Brand brand);

    ImportResult importSpecification(String tenantId, UUID runId, Specification specification);

    ImportResult importTag(String tenantId, UUID runId, Tag tag);

    /** 仅在完整且无拒绝记录的全量快照后标记来源存在/缺失，不删除业务记录。 */
    void reconcileSourcePresence(String tenantId, UUID runId, Map<String, Set<String>> seenSourceIds);

    void completeRun(String tenantId, UUID runId, RunStatistics statistics);

    void failRun(String tenantId, UUID runId, RunStatistics statistics, RuntimeException error);

    /** 单次导入根对象及其子记录的幂等处理统计。 */
    record ImportResult(
            /** 首次创建的 ERP 记录数。 */
            long created,
            /** 来源摘要变化并更新的 ERP 记录数。 */
            long changed,
            /** 来源摘要未变化而跳过的 ERP 记录数。 */
            long duplicates,
            /** 缺少必要字段而拒绝落库的来源记录数。 */
            long rejected) {
        public static ImportResult created(long count) { return new ImportResult(count, 0, 0, 0); }
        public static ImportResult changed(long count) { return new ImportResult(0, count, 0, 0); }
        public static ImportResult duplicate(long count) { return new ImportResult(0, 0, count, 0); }
        public static ImportResult rejected(long count) { return new ImportResult(0, 0, 0, count); }

        public ImportResult plus(ImportResult other) {
            return new ImportResult(created + other.created, changed + other.changed,
                    duplicates + other.duplicates, rejected + other.rejected);
        }
    }

    /** 一个 ERP 商品主数据同步批次的持久化统计。 */
    record RunStatistics(
            /** 本批次交给 ERP 导入流程并完成统计的记录总数，商品同步包含 SKU。 */
            long fetched,
            /** 首次创建的 ERP 主数据和子记录数量。 */
            long created,
            /** 来源摘要变化并更新的 ERP 主数据和子记录数量。 */
            long changed,
            /** 按来源摘要判定为重复的 ERP 主数据和子记录数量。 */
            long duplicates,
            /** 因缺少必要字段而拒绝落库的来源记录数量。 */
            long rejected,
            /** 从 Integration 读取的页数。 */
            int pages,
            /** 本批次字典快照版本与未映射来源枚举汇总。 */
            DictionaryMappingAudit dictionaryAudit) {
        public RunStatistics {
            dictionaryAudit = dictionaryAudit == null ? DictionaryMappingAudit.empty() : dictionaryAudit;
        }
    }
}
