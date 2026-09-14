package com.rigour.sales.temporarycheckin;

import static com.rigour.sales.temporarycheckin.TemporaryCheckinRiskModels.RULES_VERSION;
import com.rigour.sales.temporarycheckin.TemporaryCheckinAdminAccessPolicy.AdminScope;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRiskModels.*;
import com.rigour.sales.temporarycheckin.TemporaryCheckinRiskRepository.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 设备、原文件关联与证据版本复核；全授权历史和当前筛选分开，任何线索均不判定实际操作者。 */
@Service
@ConditionalOnProperty(prefix="rigour.sales.temporary-checkin",name="enabled",havingValue="true")
public class TemporaryCheckinRiskService {
    private static final ZoneId BUSINESS_ZONE=ZoneId.of("Asia/Shanghai");
    private final TemporaryCheckinRiskRepository repository;
    private final UUID tenant;
    private final Clock clock;
    private final TransactionTemplate transactions;
    public TemporaryCheckinRiskService(TemporaryCheckinRiskRepository repository,TemporaryCheckinProperties properties,
            Clock clock,PlatformTransactionManager manager) {
        this.repository=repository;this.tenant=properties.requireTenantId();this.clock=clock;
        this.transactions=new TransactionTemplate(manager);
    }
    public void ensureRegistry() {repository.ensureRegistry(tenant,clock.instant());}

    /** 控制器已补登记；可供既有 Workbook 的只读事务复用，不执行写入。 */
    public BatchSummary summariesPrepared(AdminScope scope,List<UUID> ids,LocalDate from,LocalDate to,String city,UUID salespersonId) {
        return evidencePrepared(scope,ids,from,to,city,salespersonId,100,false).summaries();
    }
    /** 导出一次读取所有匹配组，按拜访建立索引，避免每百条重新扫描全部关联。 */
    BatchSummary summariesForExportPrepared(AdminScope scope,List<UUID> ids,LocalDate from,LocalDate to,String city,UUID salespersonId) {
        return evidencePrepared(scope,ids,from,to,city,salespersonId,20000,false).summaries();
    }
    /** Excel 复用同一次授权事实读取；额外返回原文件证据和最近归属，禁止每页重复扫描或在只读事务补登记。 */
    ExportEvidence exportEvidencePrepared(AdminScope scope,List<UUID> ids,LocalDate from,LocalDate to,String city,UUID salespersonId) {
        return evidencePrepared(scope,ids,from,to,city,salespersonId,20000,true);
    }
    private ExportEvidence evidencePrepared(AdminScope scope,List<UUID> ids,LocalDate from,LocalDate to,String city,UUID salespersonId,int maximum,boolean export) {
        requireScope(scope);var filters=filters(scope,from,to,city,salespersonId);
        if(ids==null||ids.size()>maximum||ids.stream().anyMatch(Objects::isNull))
            throw TemporaryCheckinException.badRequest("一次最多查询"+maximum+"条有效拜访编号");
        var requested=ids.stream().distinct().toList();
        if(requested.isEmpty()) return new ExportEvidence(new BatchSummary(List.of(),RULES_VERSION),Map.of(),Map.of(),Map.of(),Map.of());
        return transactions.execute(tx->{
            Set<UUID> visible=new LinkedHashSet<>(repository.visibleIds(tenant,scope.city(),requested));
            // 不返回隐藏 ID、缺失原因或隐藏数量；调用方只得到当前权限可见记录。
            var keys=repository.groupsForSubmissions(tenant,scope.city(),new ArrayList<>(visible));
            var groups=loadGroups(scope,keys);
            Map<GroupKey,StoredReview> reviews=latest(scope,keys);
            var originalRisk=repository.oldRiskLevels(tenant,scope.city(),new ArrayList<>(visible));
            Map<UUID,List<GroupSummary>> bySubmission=new HashMap<>();
            Map<GroupKey,GroupSummary> groupSummaries=new LinkedHashMap<>();
            for(var entry:groups.entrySet()) {
                GroupSummary base=summary(scope,entry.getKey(),entry.getValue(),reviews.get(entry.getKey()),filters,null);
                if(export)groupSummaries.put(entry.getKey(),base);
                var segments=entry.getValue().stream().filter(m->m.segmentId()!=null).collect(Collectors.groupingBy(Member::submissionId,
                        Collectors.mapping(Member::segmentId,Collectors.toSet())));
                var visits=distinctVisits(entry.getValue());long earlier=0;Instant previous=null;
                for(int index=0;index<visits.size();index++) {
                    var member=visits.get(index);
                    if(!member.submittedAt().equals(previous)) {earlier=index;previous=member.submittedAt();}
                    if(!visible.contains(member.submissionId()))continue;
                    var contextual=withContext(base,earlier,segments.getOrDefault(member.submissionId(),Set.of()).stream().sorted().toList());
                    bySubmission.computeIfAbsent(member.submissionId(),ignored->new ArrayList<>()).add(contextual);
                }
            }
            List<SubmissionSummary> items=new ArrayList<>();
            for(UUID id:requested) {
                if(!visible.contains(id)) continue;
                GroupSummary device=null;List<GroupSummary> audios=new ArrayList<>();String oldLevel=originalRisk.getOrDefault(id,"NONE");
                for(var summary:bySubmission.getOrDefault(id,List.of())) {
                    if(summary.kind().equals("DEVICE")) device=summary;else audios.add(summary);
                }
                audios.sort(Comparator.comparing(GroupSummary::code));
                var reasons=reasons(device,audios);
                String level=reasons.contains("SHARED_DEVICE")||reasons.contains("AUDIO_CROSS_SALES")||reasons.contains("AUDIO_CROSS_DATE")
                        ?"HIGH":reasons.contains("AUDIO_DUPLICATE")?stronger(oldLevel,"MEDIUM"):oldLevel;
                boolean pending=(device!=null&&device.reviewStatus().equals("PENDING"))
                        ||audios.stream().anyMatch(a->a.reviewStatus().equals("PENDING"));
                items.add(new SubmissionSummary(id,device,List.copyOf(audios),reasons,level,pending));
            }
            Map<Long,AssignmentEvent> assignments=new HashMap<>();Map<GroupKey,ReviewEvent> reviewEvents=new HashMap<>();
            if(export) {
                var devices=keys.stream().filter(k->k.kind().equals("DEVICE")).map(GroupKey::id).distinct().toList();
                for(int offset=0;offset<devices.size();offset+=500)
                    assignments.putAll(repository.latestAssignments(tenant,scope.city(),devices.subList(offset,Math.min(offset+500,devices.size()))));
                reviews.forEach((key,value)->reviewEvents.put(key,value.event()));
                groups.replaceAll((key,value)->List.copyOf(value));
            }
            return new ExportEvidence(new BatchSummary(List.copyOf(items),RULES_VERSION),Map.copyOf(groupSummaries),
                    export?Map.copyOf(groups):Map.of(),Map.copyOf(assignments),Map.copyOf(reviewEvents));
        });
    }
    /** 包内导出证据，不能作为公开 HTTP DTO；各成员已限制到同一租户和管理员授权城市。 */
    record ExportEvidence(BatchSummary summaries,Map<GroupKey,GroupSummary> groupSummaries,
            Map<GroupKey,List<Member>> members,Map<Long,AssignmentEvent> latestAssignments,Map<GroupKey,ReviewEvent> latestReviews) { }

    public Page<GroupSummary> list(AdminScope scope,String kind,LocalDate from,LocalDate to,String city,UUID salesperson,
            String query,boolean duplicateOnly,boolean crossSalesOnly,String sortBy,String sortDirection,int page,int size) {
        return list(scope,kind,from,to,city,salesperson,query,duplicateOnly,crossSalesOnly,null,sortBy,sortDirection,page,size);
    }
    public Page<GroupSummary> list(AdminScope scope,String kind,LocalDate from,LocalDate to,String city,UUID salesperson,
            String query,boolean duplicateOnly,boolean crossSalesOnly,String reviewStatus,String sortBy,String sortDirection,int page,int size) {
        requireScope(scope);kind=kind(kind);var filter=filters(scope,from,to,city,salesperson);paging(page,size);
        query=optional(query,128);String order=enumValue(sortBy,Set.of("historyCount","salespersonCount","lastSubmittedAt"),"lastSubmittedAt");
        String direction=enumValue(sortDirection,Set.of("asc","desc"),"desc");String finalKind=kind;String finalQuery=query;
        String review=reviewStatus==null||reviewStatus.isBlank()?null:enumValue(reviewStatus,Set.of("PENDING","EXPLAINED","FLAGGED","INCONCLUSIVE","NOT_REQUIRED"),null);
        return transactions.execute(tx->{
            var found=repository.groupPage(tenant,scope.city(),finalKind,filter,finalQuery,duplicateOnly,crossSalesOnly,review,order,direction,page,size);
            var members=repository.members(tenant,scope.city(),finalKind,found.ids()).stream().collect(Collectors.groupingBy(Member::groupId));
            var reviews=repository.latestReviews(tenant,scope.city(),finalKind,found.ids());
            var items=found.ids().stream().filter(members::containsKey).map(id->summary(scope,new GroupKey(finalKind,id),members.get(id),reviews.get(id),filter,null)).toList();
            return TemporaryCheckinRiskRepository.page(items,page,size,found.total());
        });
    }

    public GroupDetail detail(AdminScope scope,String kind,long id,LocalDate from,LocalDate to,String city,UUID salesperson,
            int page,int size,String timelineScope,UUID contextSubmission) {
        return detail(scope,kind,id,from,to,city,salesperson,page,size,timelineScope,contextSubmission,false);
    }
    public GroupDetail detail(AdminScope scope,String kind,long id,LocalDate from,LocalDate to,String city,UUID salesperson,
            int page,int size,String timelineScope,UUID contextSubmission,boolean salespersonChangesOnly) {
        requireScope(scope);kind=kind(kind);paging(page,size);var filter=filters(scope,from,to,city,salesperson);
        if(salespersonChangesOnly&&!kind.equals("DEVICE"))throw TemporaryCheckinException.badRequest("账号变化筛选仅适用于设备拜访时间线");
        String timeline=enumValue(timelineScope,Set.of("HISTORY","FILTERED"),"HISTORY");String finalKind=kind;
        return transactions.execute(tx->{
            var members=requiredMembers(scope,finalKind,id);
            if(contextSubmission!=null&&members.stream().noneMatch(m->m.submissionId().equals(contextSubmission)))
                throw TemporaryCheckinException.notFound("关联拜访不存在");
            var last=repository.latestReviews(tenant,scope.city(),finalKind,List.of(id)).get(id);
            GroupSummary summary=summary(scope,new GroupKey(finalKind,id),members,last,filter,contextSubmission);
            var distinct=distinctVisits(members);
            List<Member> candidates=distinct;
            if(salespersonChangesOnly) {
                candidates=new ArrayList<>();Member previous=null;
                for(Member member:distinct) {
                    if(previous!=null&&!previous.salespersonId().equals(member.salespersonId()))candidates.add(member);
                    previous=member;
                }
            }
            var selected=candidates.stream().filter(m->timeline.equals("HISTORY")||matches(m,filter))
                    .sorted(Comparator.comparing(Member::submittedAt).thenComparing(m->m.submissionId().toString()).reversed()).toList();
            long offset=(long)page*size;var pageRows=offset>=selected.size()?List.<Member>of():selected.subList((int)offset,(int)Math.min(offset+size,selected.size()));
            var audio=finalKind.equals("AUDIO")?members:repository.audioForVisits(tenant,scope.city(),pageRows.stream().map(Member::submissionId).toList());
            var audioByVisit=audio.stream().collect(Collectors.groupingBy(Member::submissionId));
            var visits=pageRows.stream().map(m->new Visit(m.submissionId(),m.submittedAt(),m.city(),m.salespersonId(),m.salespersonName(),m.storeId(),m.storeName(),
                    m.locationAddress(),m.deviceId()==null?null:m.deviceId().toString(),m.deviceId()==null?null:TemporaryCheckinRiskRepository.code("DEVICE",m.deviceId()),
                    audioByVisit.getOrDefault(m.submissionId(),List.of()).stream().map(TemporaryCheckinRiskService::audioReference).distinct().toList(),matches(m,filter))).toList();
            var people=distinct.stream().collect(Collectors.groupingBy(Member::salespersonId)).entrySet().stream().map(entry->{
                var sorted=entry.getValue().stream().sorted(Comparator.comparing(Member::submittedAt).thenComparing(m->m.submissionId().toString())).toList();
                return new SalespersonSummary(entry.getKey(),sorted.getLast().salespersonName(),sorted.size(),sorted.getFirst().submittedAt(),sorted.getLast().submittedAt());
            }).sorted(Comparator.comparing(SalespersonSummary::firstSubmittedAt).thenComparing(s->s.salespersonId().toString())).toList();
            var assignments=finalKind.equals("DEVICE")?repository.assignments(tenant,scope.city(),id,0,50)
                    :TemporaryCheckinRiskRepository.<AssignmentEvent>page(List.of(),0,50,0);
            var reviewEvents=repository.reviews(tenant,scope.city(),finalKind,id,0,50);
            Instant received=null;
            if(finalKind.equals("AUDIO")&&members.stream().allMatch(m->trustedUploadedAt(m)!=null))
                received=members.stream().map(TemporaryCheckinRiskService::trustedUploadedAt).min(Comparator.naturalOrder()).orElse(null);
            return new GroupDetail(summary,people,TemporaryCheckinRiskRepository.page(visits,page,size,selected.size()),assignments.items(),reviewEvents.items(),
                    finalKind.equals("AUDIO")?members.getFirst().sha256():null,summary.sizeBytes(),received,received==null?"UNKNOWN":"SERVER_RECEIVED",
                    distinct.stream().map(Member::userAgent).filter(Objects::nonNull).filter(s->!s.isBlank()).distinct().sorted().limit(20).toList(),
                    assignments.totalElements(),reviewEvents.totalElements());
        });
    }

    public ReviewEvent review(AdminScope scope,String kind,long id,ReviewRequest request) {
        requireScope(scope);kind=kind(kind);
        if(request==null||request.clientEventId()==null) throw TemporaryCheckinException.badRequest("缺少复核幂等编号");
        String status=enumValue(request.status(),Set.of("EXPLAINED","FLAGGED","INCONCLUSIVE"),null);
        String note=note(request.note());String version=request.evidenceVersion();
        if(version==null||!version.matches("[a-f0-9]{64}")) throw TemporaryCheckinException.badRequest("缺少有效证据版本，请刷新档案");
        String finalKind=kind;String payload=hash(String.join("\n",kind,String.valueOf(id),TemporaryCheckinRiskRepository.scopeKey(scope.city()),scope.username(),status,version,note));
        try {return transactions.execute(tx->{
            repository.lockGroup(tenant,finalKind,id);
            var members=requiredMembers(scope,finalKind,id);
            var prior=repository.reviewByClient(tenant,request.clientEventId());
            if(prior!=null) {
                if(!payload.equals(prior.requestHash())) throw TemporaryCheckinException.conflict("同一操作编号对应的复核内容不同");
                return prior.event();
            }
            var key=new GroupKey(finalKind,id);
            if(!version.equals(evidenceVersion(scope,key,members))) throw TemporaryCheckinException.conflict("关联证据已变化，请刷新后重新复核");
            Instant now=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            var latest=repository.latestReviews(tenant,scope.city(),finalKind,List.of(id)).get(id);
            if(latest!=null&&!now.isAfter(latest.event().reviewedAt()))now=latest.event().reviewedAt().plusNanos(1000);
            var event=new ReviewEvent(UUID.randomUUID(),request.clientEventId(),status,note,scope.username(),now,version,RULES_VERSION,
                    memberKeys(members).size(),scope.city());
            repository.insertReview(tenant,finalKind,id,scope.city(),event,payload,memberKeys(members));return event;
        });}catch(org.springframework.dao.DuplicateKeyException duplicate) {throw TemporaryCheckinException.conflict("此操作编号已被其他复核使用");}
    }

    public AssignmentEvent assign(AdminScope scope,long id,AssignmentRequest request) {
        requireScope(scope);
        if(request==null||request.clientEventId()==null) throw TemporaryCheckinException.badRequest("缺少归属记录幂等编号");
        String type=enumValue(request.assignmentType(),Set.of("PERSONAL","SHARED","UNCONFIRMED"),null);String note=note(request.note());
        if((type.equals("PERSONAL"))!=(request.salespersonId()!=null)) throw TemporaryCheckinException.badRequest("个人保管必须选择销售；共用或未确认不指定个人");
        if(request.validFrom()!=null&&request.validTo()!=null&&request.validFrom().isAfter(request.validTo()))
            throw TemporaryCheckinException.badRequest("归属有效期开始不能晚于结束");
        checkDate(request.validFrom());checkDate(request.validTo());
        String payload=hash(String.join("\n","DEVICE",String.valueOf(id),TemporaryCheckinRiskRepository.scopeKey(scope.city()),scope.username(),type,
                String.valueOf(request.salespersonId()),String.valueOf(request.validFrom()),String.valueOf(request.validTo()),note));
        try {return transactions.execute(tx->{
            repository.lockGroup(tenant,"DEVICE",id);requiredMembers(scope,"DEVICE",id);
            var prior=repository.assignmentByClient(tenant,request.clientEventId());
            if(prior!=null) {
                if(!payload.equals(prior.requestHash())) throw TemporaryCheckinException.conflict("同一操作编号对应的归属内容不同");
                return prior.event();
            }
            String person=request.salespersonId()==null?null:repository.salespersonName(tenant,scope.city(),request.salespersonId());
            Instant now=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            var latest=repository.assignments(tenant,scope.city(),id,0,1).items();
            if(!latest.isEmpty()&&!now.isAfter(latest.getFirst().assignedAt()))now=latest.getFirst().assignedAt().plusNanos(1000);
            var event=new AssignmentEvent(UUID.randomUUID(),request.clientEventId(),type,request.salespersonId(),person,request.validFrom(),request.validTo(),note,scope.username(),now,scope.city());
            repository.insertAssignment(tenant,id,scope.city(),event,payload);return event;
        });}catch(org.springframework.dao.DuplicateKeyException duplicate) {throw TemporaryCheckinException.conflict("此操作编号已被其他归属记录使用");}
    }
    public Page<ReviewEvent> reviews(AdminScope scope,String kind,long id,int page,int size) {
        requireScope(scope);kind=kind(kind);paging(page,size);requiredMembers(scope,kind,id);return repository.reviews(tenant,scope.city(),kind,id,page,size);
    }
    public Page<AssignmentEvent> assignments(AdminScope scope,long id,int page,int size) {
        requireScope(scope);paging(page,size);requiredMembers(scope,"DEVICE",id);return repository.assignments(tenant,scope.city(),id,page,size);
    }
    public IdentityEventPage identityEvents(AdminScope scope,long id,boolean changesOnly,int page,int size) {
        requireScope(scope);paging(page,size);
        return transactions.execute(tx->{requiredMembers(scope,"DEVICE",id);return repository.identityEvents(tenant,scope.city(),id,changesOnly,page,size);});
    }

    private Map<GroupKey,List<Member>> loadGroups(AdminScope scope,List<GroupKey> keys) {
        Map<GroupKey,List<Member>> groups=new LinkedHashMap<>();
        int totalMembers=0;
        for(String kind:List.of("DEVICE","AUDIO")) {
            var ids=keys.stream().filter(k->k.kind().equals(kind)).map(GroupKey::id).distinct().toList();
            for(int offset=0;offset<ids.size();offset+=500) {
                var members=repository.members(tenant,scope.city(),kind,ids.subList(offset,Math.min(offset+500,ids.size())));
                totalMembers+=members.size();
                if(totalMembers>100000)throw TemporaryCheckinException.badRequest("关联证据超过100000项，请缩小查询或导出范围");
                members.forEach(m->groups.computeIfAbsent(new GroupKey(kind,m.groupId()),x->new ArrayList<>()).add(m));
            }
        }
        return groups;
    }
    private Map<GroupKey,StoredReview> latest(AdminScope scope,List<GroupKey> keys) {
        Map<GroupKey,StoredReview> result=new HashMap<>();
        for(String kind:List.of("DEVICE","AUDIO")) {
            var ids=keys.stream().filter(k->k.kind().equals(kind)).map(GroupKey::id).distinct().toList();
            for(int offset=0;offset<ids.size();offset+=500)
                repository.latestReviews(tenant,scope.city(),kind,ids.subList(offset,Math.min(offset+500,ids.size())))
                        .forEach((id,r)->result.put(new GroupKey(kind,id),r));
        }
        return result;
    }
    private List<Member> requiredMembers(AdminScope scope,String kind,long id) {
        if(id<1) throw TemporaryCheckinException.notFound("关联档案不存在");
        var members=repository.members(tenant,scope.city(),kind,List.of(id));
        if(members.isEmpty()) throw TemporaryCheckinException.notFound("关联档案不存在");return members;
    }
    private GroupSummary summary(AdminScope scope,GroupKey key,List<Member> members,StoredReview review,Filters filters,UUID context) {
        var visits=distinctVisits(members);Member first=visits.getFirst(),last=visits.getLast();
        long people=visits.stream().map(Member::salespersonId).distinct().count();
        String version=evidenceVersion(scope,key,members);boolean changed=review!=null&&!version.equals(review.event().evidenceVersion());
        boolean attention=key.kind().equals("DEVICE")?people>1:visits.size()>1;
        String status=!attention?"NOT_REQUIRED":review==null||changed?"PENDING":review.event().status();
        Long duration=null;String durationSource="UNKNOWN";
        if(key.kind().equals("AUDIO")) {
            duration=members.stream().map(Member::parsedDurationMs).filter(Objects::nonNull).findFirst().orElse(null);
            if(duration!=null) durationSource="SERVER_PARSED";
            else {
                // 相同文件的本机时长可能各不相同；只在已知估计一致时给出组级展示值。
                var estimates=members.stream().map(Member::clientDurationMs).filter(Objects::nonNull).distinct().toList();
                if(estimates.size()==1) {duration=estimates.getFirst();durationSource="CLIENT_ESTIMATE";}
            }
        }
        Member current=context==null?null:visits.stream().filter(m->m.submissionId().equals(context)).findFirst().orElse(null);
        Long earlier=current==null?null:visits.stream().filter(m->m.submittedAt().isBefore(current.submittedAt())).count();
        return new GroupSummary(Long.toString(key.id()),TemporaryCheckinRiskRepository.code(key.kind(),key.id()),key.kind(),visits.size(),
                visits.stream().filter(m->matches(m,filters)).count(),people,visits.stream().map(Member::storeId).filter(Objects::nonNull).distinct().count(),
                visits.stream().map(m->m.submittedAt().atZone(BUSINESS_ZONE).toLocalDate()).distinct().count(),first.submittedAt(),last.submittedAt(),first.salespersonName(),
                status,version,changed,review==null?null:review.event().reviewedAt(),review==null?null:review.event().actor(),
                key.kind().equals("AUDIO")?members.getFirst().sha256().substring(0,16):null,key.kind().equals("AUDIO")?members.getFirst().sizeBytes():null,
                duration,durationSource,current==null?null:(long)visits.size()-1,earlier,context==null?List.of():members.stream()
                    .filter(m->m.submissionId().equals(context)).map(Member::segmentId).filter(Objects::nonNull).distinct().sorted().toList());
    }
    private static AudioReference audioReference(Member m) {
        String original="/sales-checkin/admin/submissions/"+m.submissionId()+"/media/audio/"+m.segmentId();
        return new AudioReference(Long.toString(m.groupId()),TemporaryCheckinRiskRepository.code("AUDIO",m.groupId()),m.segmentId(),m.originalFilename(),
                m.parsedDurationMs(),m.clientDurationMs(),m.parsedDurationMs()!=null?m.parsedDurationMs():m.clientDurationMs(),
                m.parsedDurationMs()!=null?"SERVER_PARSED":m.clientDurationMs()!=null?"CLIENT_ESTIMATE":"UNKNOWN",original,
                "READY".equals(m.playbackStatus())?original+"?playback=true":null);
    }
    private static GroupSummary withContext(GroupSummary base,long earlier,List<String> segments) {
        return new GroupSummary(base.id(),base.code(),base.kind(),base.historyCount(),base.filterCount(),base.salespersonCount(),base.storeCount(),
                base.dateCount(),base.firstSubmittedAt(),base.lastSubmittedAt(),base.firstSalespersonName(),base.reviewStatus(),base.evidenceVersion(),
                base.newEvidence(),base.reviewedAt(),base.reviewedBy(),base.shaPrefix(),base.sizeBytes(),base.durationMs(),base.durationSource(),
                base.historyCount()-1,earlier,segments);
    }
    private String evidenceVersion(AdminScope scope,GroupKey key,List<Member> members) {
        return hash(RULES_VERSION+"\n"+tenant+"\n"+TemporaryCheckinRiskRepository.scopeKey(scope.city())+"\n"+key.kind()+"\n"+key.id()+"\n"+String.join("\n",memberKeys(members)));
    }
    private static List<String> memberKeys(List<Member> members) {return members.stream().map(Member::memberKey).distinct().sorted().toList();}
    private static List<Member> distinctVisits(List<Member> members) {
        Map<UUID,Member> unique=new LinkedHashMap<>();members.stream().sorted(Comparator.comparing(Member::submittedAt).thenComparing(m->m.submissionId().toString()))
                .forEach(m->unique.putIfAbsent(m.submissionId(),m));return List.copyOf(unique.values());
    }
    private static List<String> reasons(GroupSummary device,List<GroupSummary> audios) {
        List<String> reasons=new ArrayList<>();if(device!=null&&device.salespersonCount()>1) reasons.add("SHARED_DEVICE");
        if(audios.stream().anyMatch(a->a.historyCount()>1)) reasons.add("AUDIO_DUPLICATE");
        if(audios.stream().anyMatch(a->a.salespersonCount()>1)) reasons.add("AUDIO_CROSS_SALES");
        if(audios.stream().anyMatch(a->a.dateCount()>1)) reasons.add("AUDIO_CROSS_DATE");return List.copyOf(reasons);
    }
    private static String stronger(String left,String right) {
        var levels=List.of("NONE","LOW","MEDIUM","HIGH");return levels.get(Math.max(0,Math.max(levels.indexOf(left),levels.indexOf(right))));
    }
    private static boolean matches(Member m,Filters f) {
        return (f.from()==null||!m.submittedAt().isBefore(f.from()))&&(f.until()==null||m.submittedAt().isBefore(f.until()))
                &&(f.city()==null||f.city().equals(m.city()))&&(f.salespersonId()==null||f.salespersonId().equals(m.salespersonId()));
    }
    private static Filters filters(AdminScope scope,LocalDate from,LocalDate to,String city,UUID salesperson) {
        checkDate(from);checkDate(to);city=optional(city,64);
        if(from!=null&&to!=null&&from.isAfter(to)) throw TemporaryCheckinException.badRequest("开始日期不能晚于结束日期");
        if(scope.city()!=null&&city!=null&&!scope.city().equals(city)) throw TemporaryCheckinException.adminForbidden("不能查询其他城市");
        return new Filters(from==null?null:from.atStartOfDay(BUSINESS_ZONE).toInstant(),to==null?null:to.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant(),city,salesperson);
    }
    private static Instant trustedUploadedAt(Member m) {
        if(m.captureSource()==null||m.uploadedAtText()==null) return null;
        try {return Instant.parse(m.uploadedAtText());}catch(java.time.format.DateTimeParseException invalid) {return null;}
    }
    private static void checkDate(LocalDate date) {if(date!=null&&(date.getYear()<1970||date.getYear()>9998)) throw TemporaryCheckinException.badRequest("日期超出范围");}
    private static void requireScope(AdminScope scope) {if(scope==null||scope.username()==null||scope.username().isBlank()) throw TemporaryCheckinException.adminUnauthorized("请先登录后台");}
    private static String kind(String kind) {return enumValue(kind,Set.of("DEVICE","AUDIO"),null);}
    private static String note(String raw) {String note=optional(raw,2000);if(note==null||note.length()<2) throw TemporaryCheckinException.badRequest("请填写至少2字的依据备注");return note;}
    private static String optional(String raw,int maximum) {if(raw==null||raw.isBlank())return null;String s=raw.strip();if(s.length()>maximum)throw TemporaryCheckinException.badRequest("输入内容过长");return s;}
    private static String enumValue(String raw,Set<String> allowed,String fallback) {String value=raw==null||raw.isBlank()?fallback:raw;if(value==null||!allowed.contains(value))throw TemporaryCheckinException.badRequest("筛选或操作类型无效");return value;}
    private static void paging(int page,int size) {if(page<0||size<1||size>100)throw TemporaryCheckinException.badRequest("分页参数无效");}
    private static String hash(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
