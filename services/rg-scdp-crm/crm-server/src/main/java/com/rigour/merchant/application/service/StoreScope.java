package com.rigour.merchant.application.service;

import com.rigour.settings.api.v1.model.AdminDivisionCatalog;
import com.rigour.tenant.iam.api.v1.model.StoreAccessView;
import com.rigour.merchant.api.v1.model.StoreModels;
import java.util.*;

/** Request-local authority plus an immutable administrative version. */
public final class StoreScope {
    private final StoreAccessView access;
    private final AdminDivisionCatalog catalog;
    private final Map<String,List<String>> departmentMembers;
    private final Map<String,AdminDivisionCatalog.Node> nodes;
    private final Map<String,List<AdminDivisionCatalog.Node>> paths = new HashMap<>();
    public StoreScope(StoreAccessView access, AdminDivisionCatalog catalog, Map<String,List<String>> departmentMembers) {
        this.access=access;this.catalog=catalog;this.departmentMembers=Map.copyOf(departmentMembers);
        var index=new LinkedHashMap<String,AdminDivisionCatalog.Node>();catalog.nodes().forEach(n->index.put(n.code(),n));this.nodes=Collections.unmodifiableMap(index);
    }
    public StoreAccessView access(){return access;}
    public AdminDivisionCatalog catalog(){return catalog;}
    public Map<String,List<String>> departmentMembers(){return departmentMembers;}
    public Map<String,AdminDivisionCatalog.Node> nodes(){return nodes;}
    public List<AdminDivisionCatalog.Node> ancestry(String code) {
        if(paths.containsKey(code))return paths.get(code);
        var nodes=nodes(); var result=new ArrayList<AdminDivisionCatalog.Node>(); var seen=new HashSet<String>();
        for(var node=nodes.get(code);node!=null;node=nodes.get(node.parentCode())) {
            if(!seen.add(node.code())) throw new IllegalStateException("行政区划包含环");
            result.add(node);
        }
        var path=List.copyOf(result);paths.put(code,path);return path;
    }
    public boolean inside(String code, List<StoreAccessView.Area> areas) {
        var ancestry=ancestry(code);
        return areas.stream().anyMatch(a -> catalog.version().equals(a.version()) && ancestry.stream().anyMatch(n->n.code().equals(a.code())));
    }
    public boolean permitsArea(String code) {
        if(!nodes().containsKey(code)) return false;
        return access.grants().stream().anyMatch(g->g.national() || inside(code,g.areas()))
                && (!access.personalLimitEnabled() || inside(code,access.personalAreas()));
    }
    public StoreModels.Context context() {
        var allowed=catalog.nodes().stream().filter(n->permitsArea(n.code())).toList();
        var visible=new LinkedHashSet<String>();
        allowed.forEach(n->ancestry(n.code()).forEach(a->visible.add(a.code())));
        var allowedCodes=new HashSet<String>();allowed.forEach(n->allowedCodes.add(n.code()));
        var roots=allowed.stream().filter(n->!allowedCodes.contains(n.parentCode())).toList();
        boolean national=!access.personalLimitEnabled() && access.grants().stream().anyMatch(StoreAccessView.Grant::national);
        String label=national?"全国":roots.size()==1?roots.getFirst().name():roots.isEmpty()?"未配置地区范围":"已授权的"+roots.size()+"个地区";
        return new StoreModels.Context(catalog.version(),label,national,
                catalog.nodes().stream().filter(n->visible.contains(n.code())).map(n->new StoreModels.Area(n.code(),n.name(),n.level(),n.parentCode(),n.regionCode())).toList(),
                access.permissions(),access.employeeId(),allowed.isEmpty()?"尚无可用的门店地区权限，请联系管理员配置部门负责地区或个人范围":null);
    }
}
