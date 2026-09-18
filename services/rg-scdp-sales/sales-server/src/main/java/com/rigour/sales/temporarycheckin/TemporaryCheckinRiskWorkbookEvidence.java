package com.rigour.sales.temporarycheckin;

import com.rigour.sales.temporarycheckin.TemporaryCheckinRiskModels.AssignmentEvent;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRiskModels.GroupSummary;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRiskModels.ReviewEvent;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRiskModels.SubmissionSummary;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRiskRepository.Member;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** 将一次授权快照整理为导出证据；同文件同拜访的多个片段合并，筛选命中以实际导出编号为准。 */
record TemporaryCheckinRiskWorkbookEvidence(Map<UUID,SubmissionSummary> submissions,
        List<Group> devices,List<Group> audios,String scopeLabel,String rulesVersion) {

    static TemporaryCheckinRiskWorkbookEvidence from(TemporaryCheckinRiskService.ExportEvidence source,
            List<UUID> exportedIds,String scopeCity) {
        Objects.requireNonNull(source,"风控导出证据未加载");
        Set<UUID> matched=Set.copyOf(exportedIds);
        Map<UUID,SubmissionSummary> summaries=new HashMap<>();
        source.summaries().items().forEach(item->summaries.put(item.submissionId(),item));
        List<Group> devices=new ArrayList<>(),audios=new ArrayList<>();
        source.members().entrySet().stream().sorted(Map.Entry.comparingByKey(
                Comparator.comparing(TemporaryCheckinRiskRepository.GroupKey::kind)
                        .thenComparingLong(TemporaryCheckinRiskRepository.GroupKey::id))).forEach(entry->{
            var key=entry.getKey();var summary=Objects.requireNonNull(source.groupSummaries().get(key),"缺少关联组摘要");
            Map<UUID,List<Member>> membersByVisit=new LinkedHashMap<>();
            entry.getValue().stream().sorted(Comparator.comparing(Member::submittedAt)
                    .thenComparing(m->m.submissionId().toString())
                    .thenComparing(Member::segmentId,Comparator.nullsFirst(Comparator.naturalOrder())))
                    .forEach(member->membersByVisit.computeIfAbsent(member.submissionId(),ignored->new ArrayList<>()).add(member));
            var unique=membersByVisit.values().stream().map(List::getFirst).toList();
            Map<UUID,Long> salesCounts=unique.stream().collect(Collectors.groupingBy(Member::salespersonId,Collectors.counting()));
            List<Visit> visits=new ArrayList<>();Instant previous=null;long earlier=0;
            for(int index=0;index<unique.size();index++) {
                Member member=unique.get(index);
                // 同一秒或微秒同时提交没有可证明的先后，不用 UUID 排序制造“更早”。
                if(!member.submittedAt().equals(previous)) {previous=member.submittedAt();earlier=index;}
                visits.add(new Visit(member,List.copyOf(membersByVisit.get(member.submissionId())),
                        salesCounts.get(member.salespersonId()),earlier,matched.contains(member.submissionId())));
            }
            Group group=new Group(summary,List.copyOf(visits),visits.stream().filter(Visit::matchesFilter).count(),
                    "DEVICE".equals(key.kind())?source.latestAssignments().get(key.id()):null,source.latestReviews().get(key));
            if("DEVICE".equals(key.kind()))devices.add(group);else audios.add(group);
        });
        return new TemporaryCheckinRiskWorkbookEvidence(Map.copyOf(summaries),List.copyOf(devices),List.copyOf(audios),
                scopeCity==null?"当前管理员权限内全部城市":"当前管理员授权城市："+scopeCity,source.summaries().rulesVersion());
    }

    record Group(GroupSummary summary,List<Visit> visits,long matchedCount,AssignmentEvent assignment,ReviewEvent review) { }
    record Visit(Member member,List<Member> segments,long salespersonHistoryCount,long earlierCount,boolean matchesFilter) { }
}
