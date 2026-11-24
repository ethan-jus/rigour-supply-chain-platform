package com.rigour.sales.application.service;

import com.rigour.hr.api.v1.model.HrFieldworkOrganization;
import com.rigour.merchant.api.v1.model.FieldworkSources;
import com.rigour.sales.api.v1.model.FieldworkReport;
import com.rigour.sales.api.v1.model.FieldworkReport.*;
import com.rigour.sales.api.v1.model.FieldworkReport.Period;
import com.rigour.sales.application.port.out.*;
import com.rigour.sales.application.port.out.FieldworkFacts.*;
import com.rigour.sales.application.port.out.SalesManagementDirectory.Access;
import com.rigour.shared.context.*;
import com.rigour.shared.core.exception.RequestValidationException;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;

@Service
public class FieldworkReportService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String READ = "sales:dashboard:read";
    private final FieldworkFacts facts;
    private final FieldworkDirectory directory;
    private final SalesManagementScope scopes;
    private final Clock clock;

    public FieldworkReportService(
            FieldworkFacts facts,
            FieldworkDirectory directory,
            SalesManagementScope scopes,
            Clock clock) {
        this.facts = facts;
        this.directory = directory;
        this.scopes = scopes;
        this.clock = clock;
    }

    public record Filters(
            String city,
            String department,
            boolean descendants,
            UUID person,
            String status,
            boolean pending,
            String order,
            int page,
            int size,
            String employeeCode) {
        public Filters(String city, String department, boolean descendants, UUID person,
                       String status, boolean pending, String order, int page, int size) {
            this(city, department, descendants, person, status, pending, order, page, size, null);
        }
    }

    private record Entry(Employee employee, Set<String> stores, Set<String> contacts) {}

    private record Org(
            String name, String city, String dept, String deptName, Map<Long, String> ancestors) {
        boolean department(String id, boolean children) {
            return blank(id)
                    || id.equals(dept)
                    || children
                            && ancestors.keySet().stream().anyMatch(a -> a.toString().equals(id));
        }
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }

    private static Org org(HrFieldworkOrganization o, String fallback) {
        return o == null
                ? new Org(fallback, null, null, null, Map.of())
                : new Org(
                        o.employeeName(),
                        o.city(),
                        Objects.toString(o.departmentId(), null),
                        o.departmentName(),
                        o.departmentAncestors());
    }

    private static boolean cityMatch(String org, String filter) {
        return blank(filter)
                || Objects.equals(org, filter)
                || org == null && "__unknown".equals(filter);
    }

    private void validate(LocalDate from, LocalDate to, int page, int size) {
        if (from == null
                || to == null
                || from.isAfter(to)
                || from.plusYears(1).isBefore(to)
                || page < 1
                || page > 100000
                || size < 1
                || size > 100) throw new RequestValidationException("工作日或分页范围无效");
    }

    private Access access(CallerIdentity caller) {
        AuthorizationContext.requirePermission(READ);
        return scopes.resolve(caller, READ);
    }

    private static <T> List<T> page(List<T> all, int page, int size) {
        int start = Math.min(all.size(), (page - 1) * size);
        return List.copyOf(all.subList(start, Math.min(all.size(), start + size)));
    }

    private static <K, V> Map<K, List<V>> group(List<V> rows, Function<V, K> key) {
        return rows.stream().collect(Collectors.groupingBy(key));
    }

    private Map<String, HrFieldworkOrganization> organizations(
            UUID tenant, LocalDate day, Instant now, List<String> codes) {
        Instant last = day.plusDays(1).atStartOfDay(ZONE).toInstant().minusNanos(1000);
        var result = new HashMap<String, HrFieldworkOrganization>();
        var unique = codes.stream().filter(Objects::nonNull).distinct().toList();
        for (int i = 0; i < unique.size(); i += 200)
            for (var o :
                    directory.organization(
                            tenant,
                            now.isBefore(last) ? now : last,
                            unique.subList(i, Math.min(i + 200, unique.size()))))
                result.put(o.employeeCode(), o);
        return result;
    }

    private FieldworkSources.Result sources(
            UUID tenant, LocalDate day, Instant now, List<String> codes, List<VisitFact> visits) {
        var staff = codes.stream().filter(Objects::nonNull).distinct().toList();
        var ids =
                visits.stream()
                        .map(VisitFact::crmVisitId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        var contacts = new HashMap<String, FieldworkSources.Contacts>();
        var states = new HashMap<Long, FieldworkSources.Visit>();
        for (int i = 0; i < Math.max(staff.size(), ids.size()); i += 200) {
            var employees =
                    staff.subList(Math.min(i, staff.size()), Math.min(i + 200, staff.size()));
            var crmVisits = ids.subList(Math.min(i, ids.size()), Math.min(i + 200, ids.size()));
            try {
                var response =
                        directory.sources(
                                tenant, new FieldworkSources.Query(day, now, employees, crmVisits));
                contacts.putAll(response.contacts());
                states.putAll(response.visits());
            } catch (org.springframework.web.client.RestClientException
                    | IllegalStateException unavailable) {
                for (String id : employees)
                    contacts.put(id, new FieldworkSources.Contacts("UNAVAILABLE", null, List.of()));
            }
        }
        return new FieldworkSources.Result(contacts, states);
    }

    private static Visit visit(VisitFact fact, Org org, FieldworkSources.Result source) {
        var v = fact.visit();
        var remote = fact.crmVisitId() == null ? null : source.visits().get(fact.crmVisitId());
        String state =
                fact.crmVisitId() == null
                        ? v.assessment()
                        : v.departed() == null
                                ? "IN_PROGRESS"
                                : remote == null ? "UNKNOWN" : remote.state();
        return new Visit(
                v.id(),
                v.profileId(),
                v.date(),
                v.employee(),
                org.name(),
                v.store(),
                v.customer(),
                v.status(),
                v.arrived(),
                v.departed(),
                v.result(),
                v.outcome(),
                v.intention(),
                v.planId(),
                v.sequence(),
                state,
                remote == null ? v.reason() : remote.reason(),
                remote == null ? v.ruleVersion() : remote.ruleVersion(),
                fact.crmVisitId() == null
                        ? v.minimumMinutes()
                        : remote == null ? null : remote.minimumMinutes(),
                v.photos(),
                v.files(),
                v.verifiedFiles(),
                v.verifiedMillis(),
                v.objective(),
                org.city(),
                org.deptName(),
                v.receivedAt(),
                v.hasStorefrontPhoto(),
                v.playbackMillis(), v.communicationResult());
    }

    public FieldworkReport report(LocalDate date, Filters f) {
        return report(date, f, false);
    }

    public FieldworkReport report(LocalDate date, Filters f, boolean riskQueue) {
        if (riskQueue) AuthorizationContext.requirePermission("sales:risk:read");
        validate(date, date, f.page(), f.size());
        if (date.isAfter(LocalDate.now(clock.withZone(ZONE))))
            throw new RequestValidationException("工作日报不能查询未来日期");
        var caller = SalesWorkContextService.requireTenantCaller();
        var scope = access(caller);
        var tenant = caller.tenantId();
        var now = clock.instant();
        var roster = facts.roster(tenant, date, scope);
        var orgs = organizations(tenant, date, now, roster.stream().map(Profile::code).toList());
        var rosterVisits = facts.visits(tenant, date, date, scope, null);
        var source =
                sources(
                        tenant,
                        date,
                        now,
                        roster.stream().map(Profile::hrId).toList(),
                        rosterVisits);
        var punches = group(facts.punches(tenant, date, scope), StaffPunch::profile);
        var plans = group(facts.plans(tenant, date, scope), StaffPlan::profile);
        var issues = group(facts.issues(tenant, date, scope), StaffIssue::profile);
        var visits = group(rosterVisits, v -> v.visit().profileId());
        Access risk =
                caller.permissions().contains("sales:risk:read")
                        ? scopes.resolve(caller, "sales:risk:read")
                        : new Access(false, Set.of());
        var matched = new ArrayList<Entry>();
        var byCity = new TreeMap<String, List<Entry>>();
        var departments = new TreeMap<String, String>();
        var people = new ArrayList<Option>();
        for (var p : roster) {
            if (riskQueue && !risk.allows(p.code())) continue;
            var o = org(orgs.get(p.code()), p.number());
            var entry =
                    employee(
                            p,
                            o,
                            now,
                            punches.getOrDefault(p.id(), List.of()),
                            plans.getOrDefault(p.id(), List.of()),
                            visits.getOrDefault(p.id(), List.of()),
                            issues.getOrDefault(p.id(), List.of()),
                            risk.allows(p.code()),
                            source);
            byCity.computeIfAbsent(
                            o.city() == null ? "__unknown" : o.city(), k -> new ArrayList<>())
                    .add(entry);
            if (!cityMatch(o.city(), f.city())) continue;
            o.ancestors().forEach((id, label) -> departments.putIfAbsent(id.toString(), label));
            if (o.dept() != null) departments.put(o.dept(), o.deptName());
            if (!o.department(f.department(), f.descendants())) continue;
            people.add(new Option(p.id().toString(), o.name()));
            var row = entry.employee();
            if ((f.person() == null || f.person().equals(p.id()))
                    && (blank(f.employeeCode()) || f.employeeCode().equalsIgnoreCase(p.code()))
                    && (blank(f.status()) || f.status().equals(row.status()))
                    && (!f.pending() || row.pendingReviewCount() > 0)) matched.add(entry);
        }
        Comparator<Entry> order =
                switch (Objects.toString(f.order(), "")) {
                    case "visits" -> Comparator.comparingInt(x -> x.employee().visitCount());
                    case "distance" ->
                            Comparator.comparingInt(
                                    x ->
                                            Objects.requireNonNullElse(
                                                    x.employee().distanceMeters(), -1));
                    default -> Comparator.comparingInt(x -> x.employee().pendingReviewCount());
                };
        matched.sort(
                order.reversed()
                        .thenComparing(x -> Objects.toString(x.employee().employeeName(), ""))
                        .thenComparing(x -> x.employee().salesProfileId()));
        people.sort(
                Comparator.comparing(Option::name, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Option::id));
        return new FieldworkReport(
                date,
                now,
                page(matched, f.page(), f.size()).stream().map(Entry::employee).toList(),
                matched.size(),
                summarize(matched),
                byCity.entrySet().stream()
                        .map(
                                c -> {
                                    var sum = summarize(c.getValue());
                                    return new City(
                                            c.getKey(),
                                            sum.people(),
                                            sum.stores(),
                                            sum.pendingPeople());
                                })
                        .toList(),
                departments.entrySet().stream()
                        .map(a -> new Option(a.getKey(), a.getValue()))
                        .toList(),
                people);
    }

    private static Entry employee(
            Profile p,
            Org org,
            Instant now,
            List<StaffPunch> staffPunches,
            List<StaffPlan> scheduled,
            List<VisitFact> facts,
            List<StaffIssue> cases,
            boolean risk,
            FieldworkSources.Result source) {
        var punches = staffPunches.stream().map(StaffPunch::punch).toList();
        var periods = new ArrayList<Period>();
        Instant start = null;
        long seconds = 0;
        for (var event : punches) {
            if ("CHECK_IN".equals(event.type())) start = event.received();
            else if (start != null) {
                periods.add(new Period(start, event.received()));
                seconds += Math.max(0, Duration.between(start, event.received()).toSeconds());
                start = null;
            }
        }
        String status = "MISSING_CHECK_OUT".equals(p.status()) ? "MISSING_CHECKOUT" : p.status();
        if (start != null) {
            periods.add(new Period(start, null));
            if ("ACTIVE".equals(status) && p.cutoff() != null && now.isBefore(p.cutoff()))
                seconds += Math.max(0, Duration.between(start, now).toSeconds());
            else status = "MISSING_CHECKOUT";
        }
        var visits = new ArrayList<Visit>();
        var stores = new HashSet<String>();
        var planned = scheduled.stream().map(StaffPlan::id).collect(Collectors.toSet());
        var donePlans = new HashSet<UUID>();
        int done = 0, valid = 0, files = 0, verified = 0;
        long millis = 0;
        for (var fact : facts) {
            var v = visit(fact, org, source);
            visits.add(v);
            files += v.files();
            verified += v.verifiedFiles();
            millis += v.verifiedMillis();
            if (v.departed() != null) {
                done++;
                if (fact.storeKey() != null) stores.add(fact.storeKey());
                if (planned.contains(v.planId())) donePlans.add(v.planId());
            }
            if ("VALID".equals(v.assessment())) valid++;
        }
        var coverage = source.contacts().get(p.hrId());
        boolean covered = coverage != null && "READY".equals(coverage.status());
        var contacts =
                covered
                        ? coverage.additions().stream()
                                .map(FieldworkSources.Contact::key)
                                .collect(Collectors.toSet())
                        : Set.<String>of();
        var row =
                new Employee(
                        p.id(),
                        p.code(),
                        org.name(),
                        org.city(),
                        org.deptName(),
                        p.day(),
                        status,
                        punches.size(),
                        stores.size(),
                        done,
                        valid,
                        planned.size(),
                        donePlans.size(),
                        Math.toIntExact(seconds / 60),
                        files,
                        verified,
                        millis,
                        p.distance(),
                        p.gaps(),
                        p.pending(),
                        covered ? contacts.size() : null,
                        coverage == null ? "NOT_CONNECTED" : coverage.status(),
                        coverage == null ? null : coverage.observedAt(),
                        covered
                                ? coverage.additions().stream()
                                        .map(FieldworkSources.Contact::addedAt)
                                        .toList()
                                : List.of(),
                        periods,
                        punches,
                        visits,
                        risk ? cases.stream().map(StaffIssue::issue).toList() : List.of(),
                        cases.size());
        return new Entry(row, stores, contacts);
    }

    private static Summary summarize(List<Entry> entries) {
        int punched = 0,
                signedInPeople = 0,
                visitingPeople = 0,
                punches = 0,
                visits = 0,
                plans = 0,
                done = 0,
                active = 0,
                missing = 0,
                files = 0,
                verified = 0,
                withDistance = 0,
                withContacts = 0,
                risk = 0;
        long minutes = 0, millis = 0, meters = 0;
        var stores = new HashSet<String>();
        var contacts = new HashSet<String>();
        for (var e : entries) {
            var r = e.employee();
            stores.addAll(e.stores());
            contacts.addAll(e.contacts());
            if (r.punchCount() > 0) punched++;
            if (r.punches().stream().anyMatch(p -> "CHECK_IN".equals(p.type()))) signedInPeople++;
            if (r.visits().stream().anyMatch(v -> v.arrived() != null && v.departed() == null)) visitingPeople++;
            punches += r.punchCount();
            visits += r.visitCount();
            plans += r.planned();
            done += r.completedPlans();
            minutes += r.workMinutes();
            if ("ACTIVE".equals(r.status())) active++;
            if ("MISSING_CHECKOUT".equals(r.status())) missing++;
            files += r.files();
            verified += r.verifiedFiles();
            millis += r.verifiedMillis();
            if (r.distanceMeters() != null) {
                withDistance++;
                meters += r.distanceMeters();
            }
            if (r.newContacts() != null) withContacts++;
            if (r.pendingReviewCount() > 0) risk++;
        }
        return new Summary(
                entries.size(),
                punched,
                punches,
                stores.size(),
                visits,
                plans,
                done,
                minutes,
                active,
                missing,
                files,
                verified,
                millis,
                meters,
                withDistance,
                contacts.size(),
                withContacts,
                risk,
                signedInPeople,
                visitingPeople);
    }

    public List<Commitment> commitments(
            String status, LocalDate from, LocalDate through, String ownerCode, int page, int size) {
        if (!Set.of("PLANNED", "COMPLETED").contains(status)
                || page < 1
                || page > 100000
                || size < 1
                || size > 100
                || (ownerCode != null && (ownerCode.isBlank() || ownerCode.length() > 50)))
            throw new RequestValidationException("无效的补货任务筛选");
        if (from != null || through != null) validate(from, through, page, size);
        var caller = SalesWorkContextService.requireTenantCaller();
        var rows =
                facts.commitments(
                        caller.tenantId(), access(caller), status, from, through, ownerCode,
                        size + 1, (page - 1) * size);
        var names =
                organizations(
                        caller.tenantId(),
                        LocalDate.now(clock.withZone(ZONE)),
                        clock.instant(),
                        rows.stream().map(Commitment::employee).toList());
        return rows.stream()
                .map(r -> {
                            var owner = org(names.get(r.employee()), r.salesperson());
                            return new Commitment(
                                        r.id(),
                                        r.profileId(),
                                        r.employee(),
                                        owner.name(),
                                        r.store(),
                                        r.due(),
                                        r.status(),
                                        r.visitId(),
                                        r.visitDate(),
                                        r.updated(),
                                        r.storeId(),
                                        owner.city());
                        })
                .toList();
    }

    public VisitContent visitContent(UUID visitId) {
        var caller = SalesWorkContextService.requireTenantCaller();
        return facts.visitContent(caller.tenantId(), access(caller), visitId)
                .orElseThrow(() -> new AuthorizationDeniedException(READ));
    }

    public Ledger ledger(
            LocalDate from,
            LocalDate to,
            UUID person,
            String city,
            String department,
            boolean descendants,
            String keyword,
            String assessment,
            String origin,
            UUID visitId,
            String employeeCode,
            int page,
            int size) {
        return ledger(from, to, person, city, department, descendants, keyword, assessment, origin,
                visitId, employeeCode, page, size, "date", false);
    }

    public Ledger ledger(LocalDate from, LocalDate to, UUID person, String city, String department,
            boolean descendants, String keyword, String assessment, String origin, UUID visitId,
            String employeeCode, int page, int size, String sort, Boolean ascending) {
        boolean byStore = "store".equals(sort);
        var order = byStore ? ledgerOrder("date", false) : ledgerOrder(sort, ascending);
        var storeGroups = new TreeMap<String, List<Visit>>();
        if ((from == null) != (to == null) || from != null && from.isAfter(to)
                || page < 1 || page > 100000 || size < 1 || size > 200)
            throw new RequestValidationException("拜访日期或分页范围无效");
        if (keyword != null && keyword.length() > 80)
            throw new RequestValidationException("搜索条件过长");
        var caller = SalesWorkContextService.requireTenantCaller();
        var tenant = caller.tenantId();
        var now = clock.instant();
        if (!blank(origin) && !Set.of("PLANNED", "UNPLANNED").contains(origin))
            throw new RequestValidationException("拜访来源无效");
        var scope = access(caller);
        var rows = facts.visits(tenant, from, to, scope, null);
        var cities = new TreeSet<String>();
        var visibleDepartments = new HashSet<String>();
        var people = new LinkedHashMap<String, String>();
        var byDay = group(rows, v -> v.visit().date());
        var result = new ArrayList<Visit>();
        var stores = new HashSet<String>();
        int done = 0, pending = 0;
        var assessmentCounts = new HashMap<String, Integer>();
        String term = Objects.toString(keyword, "").strip().toLowerCase(Locale.ROOT);
        var sources = sources(tenant, to == null ? LocalDate.ofInstant(now, ZONE) : to, now, List.of(), rows);
        for (var day : byDay.entrySet()) {
            var orgs =
                    organizations(
                            tenant,
                            day.getKey(),
                            now,
                            day.getValue().stream().map(v -> v.visit().employee()).toList());
            for (var fact : day.getValue()) {
                var o = org(orgs.get(fact.visit().employee()), fact.visit().name());
                cities.add(o.city() == null ? "__unknown" : o.city());
                if (!cityMatch(o.city(), city)) continue;
                o.ancestors().keySet().forEach(id -> visibleDepartments.add(id.toString()));
                if (o.dept() != null) visibleDepartments.add(o.dept());
                if (!o.department(department, descendants)) continue;
                var raw = fact.visit();
                people.put(raw.profileId().toString(), o.name());
                if (!blank(employeeCode) && !employeeCode.equals(raw.employee())) continue;
                if (person != null && !person.equals(raw.profileId())
                        || visitId != null && !visitId.equals(raw.id())) continue;
                if ("PLANNED".equals(origin) && raw.planId() == null
                        || "UNPLANNED".equals(origin) && raw.planId() != null) continue;
                var v = visit(fact, o, sources);
                String storeName = blank(v.store()) ? v.customer() : v.store();
                if (!term.isEmpty() && !Objects.toString(storeName, "").toLowerCase(Locale.ROOT).contains(term))
                    continue;
                assessmentCounts.merge(v.assessment(), 1, Integer::sum);
                if (!blank(assessment) && !assessment.equals(v.assessment())) continue;
                if (byStore) {
                    // Unlinked visits only have the displayed name available for grouping.
                    String key = fact.storeKey() != null ? fact.storeKey() : "name:" + Objects.toString(storeName, "");
                    storeGroups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(v);
                } else {
                    result.add(v);
                }
                if (v.departed() != null) {
                    done++;
                    if (fact.storeKey() != null) stores.add(fact.storeKey());
                }
                if ("PENDING".equals(v.assessment())) pending++;
            }
        }
        if (byStore) {
            storeGroups.values().forEach(visits -> visits.sort(order));
            var collator = java.text.Collator.getInstance(Locale.SIMPLIFIED_CHINESE);
            Comparator<String> names = collator::compare;
            if (Boolean.FALSE.equals(ascending)) names = names.reversed();
            // Use the latest named snapshot so a rename does not split the store's history.
            storeGroups.values().stream()
                    .sorted(Comparator.comparing(FieldworkReportService::latestStoreName, Comparator.nullsLast(names)))
                    .forEachOrdered(result::addAll);
        } else {
            result.sort(order);
        }
        var items = page(result, page, size);
        return new Ledger(
                items,
                result.size(),
                stores.size(),
                done,
                pending,
                List.copyOf(cities),
                directory.salesDepartments(tenant).entrySet().stream()
                        .filter(entry -> scope.all() || visibleDepartments.contains(entry.getKey()))
                        .map(x -> new Option(x.getKey(), x.getValue()))
                        .toList(),
                people.entrySet().stream()
                        .map(x -> new Option(x.getKey(), x.getValue()))
                        .sorted(
                                Comparator.comparing(
                                                Option::name,
                                                Comparator.nullsLast(Comparator.naturalOrder()))
                                        .thenComparing(Option::id))
                        .toList(),
                Map.copyOf(assessmentCounts),
                facts.storeVisitCounts(tenant, scope, items.stream().map(Visit::id).toList()),
                storeRegions(tenant, rows, items));
    }

    private Map<UUID, String> storeRegions(UUID tenant, List<VisitFact> rows, List<Visit> items) {
        var pageIds = items.stream().map(Visit::id).collect(Collectors.toSet());
        var stores = new HashMap<UUID, Long>();
        for (var row : rows) {
            if (pageIds.contains(row.visit().id()) && row.crmStoreId() != null)
                stores.put(row.visit().id(), row.crmStoreId());
        }
        if (stores.isEmpty()) return Map.of();
        try {
            var names = directory.storeRegions(tenant, stores.values().stream().distinct().toList());
            var regions = new HashMap<UUID, String>();
            stores.forEach((visit, store) -> {
                var region = names.get(store);
                if (region != null) regions.put(visit, region);
            });
            return regions;
        } catch (org.springframework.web.client.RestClientException | IllegalStateException unavailable) {
            org.slf4j.LoggerFactory.getLogger(FieldworkReportService.class)
                    .warn("CRM 门店地区暂不可用", unavailable);
            return Map.of();
        }
    }
    private static String latestStoreName(List<Visit> visits) {
        for (Visit visit : visits) {
            if (!blank(visit.store())) return visit.store();
            if (!blank(visit.customer())) return visit.customer();
        }
        return null;
    }

    static Comparator<Visit> ledgerOrder(String sort, Boolean ascending) {
        boolean ascendingOrder = ascending != null ? ascending : Set.of("person", "department").contains(sort);
        Comparator<Instant> times = ascendingOrder ? Comparator.naturalOrder() : Comparator.reverseOrder();
        var newest = Comparator.comparing(Visit::date, Comparator.reverseOrder())
                .thenComparing(Visit::arrived, Comparator.reverseOrder());
        Comparator<Visit> primary = switch (sort) {
            case "date" -> ascendingOrder ? Comparator.comparing(Visit::date) : newest;
            case "arrived" -> Comparator.comparing(Visit::arrived, times);
            case "departed" -> Comparator.comparing(Visit::departed, Comparator.nullsLast(times));
            case "person", "department" -> {
                var collator = java.text.Collator.getInstance(Locale.SIMPLIFIED_CHINESE);
                Comparator<String> names = ascendingOrder ? collator::compare : (a, b) -> collator.compare(b, a);
                yield Comparator.comparing("person".equals(sort) ? Visit::name : Visit::departmentName,
                        Comparator.nullsLast(names));
            }
            default -> throw new RequestValidationException("不支持的拜访排序字段");
        };
        return primary.thenComparing(newest).thenComparing(Visit::profileId)
                .thenComparingInt(Visit::sequence).thenComparing(Visit::id);
    }

}
