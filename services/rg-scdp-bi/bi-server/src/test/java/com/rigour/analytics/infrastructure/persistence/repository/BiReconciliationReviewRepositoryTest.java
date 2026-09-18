package com.rigour.analytics.infrastructure.persistence.repository;

import com.rigour.analytics.api.v1.model.BiReconciliationReview;
import com.rigour.analytics.api.v1.model.BiReconciliationReview.Version;
import com.rigour.analytics.infrastructure.persistence.mapper.BiReconciliationReviewMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BiReconciliationReviewRepositoryTest {
    private final BiReconciliationReviewMapper mapper=mock(BiReconciliationReviewMapper.class);
    private final JsonMapper json=JsonMapper.builder().build();
    private final MybatisBiReconciliationReviewRepository repository=new MybatisBiReconciliationReviewRepository(mapper,json);

    @Test void snapshotRoundTripPreservesVersionAndNullMetadata() {
        var now=Instant.parse("2026-09-12T09:00:00Z");
        var version=new Version("batch","source.xlsx","a".repeat(64),null,now,"PREFLIGHTED",2);
        var review=new BiReconciliationReview("id",now,now,now,now,version,null,"UNVERIFIED","UNVERIFIED",false,null,List.of("来源未验证"),List.of());
        repository.save("tenant","actor",review);
        verify(mapper).insert("tenant","actor","id","batch",LocalDateTime.ofInstant(now,ZoneOffset.UTC),
                LocalDateTime.ofInstant(now,ZoneOffset.UTC),json.writeValueAsString(review));
        when(mapper.find("tenant","actor","id")).thenReturn(json.writeValueAsString(review));
        assertThat(repository.find("tenant","actor","id")).contains(review);
        assertThat(repository.find("other-tenant","actor","id")).isEmpty();
    }
    @Test void ordinaryGetAndHistoryNeverQuerySourceOrBusinessDatabases() throws Exception {
        for (var method:BiReconciliationReviewMapper.class.getDeclaredMethods()) {
            if (!List.of("find","history").contains(method.getName())) continue;
            String sql=String.join("\n",method.getAnnotation(Select.class).value());
            assertThat(sql).contains("bi_reconciliation_review","tenant_id=#{tenant}","actor_id=#{actor}")
                    .doesNotContain("rigour_order.","rigour_integration.","rigour_crm.");
        }
    }
    @Test void explicitCaptureQueriesAreTenantScopedAndBounded() {
        for (var method:BiReconciliationReviewMapper.class.getDeclaredMethods()) {
            if (!List.of("sourceRows","businessOrders","businessLines","biOrders","biLines").contains(method.getName())) continue;
            String sql=String.join("\n",method.getAnnotation(Select.class).value());
            assertThat(sql).contains("#{tenant}","LIMIT #{limit}","ORDER BY");
            assertThat(sql).doesNotContain("INSERT ","UPDATE ","DELETE ");
        }
    }
    @Test void sourceVersionUsesUploadTimeNotAFabricatedOnlineTime() {
        when(mapper.version("t","b")).thenReturn(Map.of("batchId","b","fileName","source.xlsx","checksum","abc",
                "uploadedAt",LocalDateTime.of(2026,9,12,9,0),"importStatus","PREFLIGHTED","rowCount",0L));
        var version=repository.version("t","b").orElseThrow();
        assertThat(version.rowCount()).isZero();
        assertThat(version.uploadedAt()).isEqualTo(Instant.parse("2026-09-12T09:00:00Z"));
        assertThat(version.sourceUrl()).isNull();
    }
    @Test void unboundLinesRetainUniqueLineIdentityAndCannotBeCertifiedByNames() {
        when(mapper.businessOrders("t",10)).thenReturn(List.of());
        when(mapper.businessLines("t",10)).thenReturn(List.of(
                Map.of("orderNo","DD1","kind","SKU","identityKey","LINE:1","associationEvidence","UNLINKED","amount",10),
                Map.of("orderNo","DD1","kind","SKU","identityKey","LINE:2","associationEvidence","UNLINKED","amount",20)));
        var rows=repository.businessRows("t",10);
        assertThat(rows).extracting(BiReconciliationReview.Fact::key).containsExactly("SKU|DD1|LINE:1","SKU|DD1|LINE:2");
        assertThat(rows).allSatisfy(f->{
            assertThat(f.associationEvidence()).isEqualTo("UNLINKED");
            assertThat(f.uncertainties()).anyMatch(i->i.contains("不合并未知商品"));
        });
        for (var method:BiReconciliationReviewMapper.class.getDeclaredMethods()) {
            if (!List.of("businessLines","biLines").contains(method.getName())) continue;
            assertThat(String.join("\n",method.getAnnotation(Select.class).value())).contains("CONCAT('LINE:'", "AS associationEvidence");
        }
    }
}
