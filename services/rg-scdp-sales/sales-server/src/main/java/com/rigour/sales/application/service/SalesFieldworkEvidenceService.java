package com.rigour.sales.application.service;

import com.rigour.sales.api.v1.model.SalesFieldworkEvidenceModels.*;
import com.rigour.sales.application.port.out.SalesFieldworkEvidenceRepository;
import com.rigour.sales.application.port.out.SalesWorkAttendanceRepository;
import com.rigour.shared.core.exception.RequestValidationException;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class SalesFieldworkEvidenceService {
    private final SalesFieldworkEvidenceRepository repository;
    private final SalesWorkAttendanceRepository days;
    private final SalesFieldworkService fieldwork;
    public SalesFieldworkEvidenceService(SalesFieldworkEvidenceRepository repository,SalesWorkAttendanceRepository days,SalesFieldworkService fieldwork) {
        this.repository=repository;this.days=days;this.fieldwork=fieldwork;
    }
    public DeviceEvidence devices(UUID profile,UUID day) {
        var caller=SalesWorkContextService.requireTenantCaller();
        fieldwork.requireProfile(profile,"sales:risk:read");
        if(days.findWorkDay(caller.tenantId(),profile,day).isEmpty()) throw new RequestValidationException("当日作业不存在");
        var events=repository.devices(caller.tenantId(),profile,day);
        var references=events.stream().map(DeviceEvent::installationReference).filter(Objects::nonNull).distinct().toList();
        String comparison=references.size()>1?"DIFFERENT":events.isEmpty()||events.stream().anyMatch(e->e.installationReference()==null)?"UNKNOWN":"SAME";
        return new DeviceEvidence(day,"NOT_OBTAINED","NOT_CONNECTED",comparison,events);
    }
    public VisitHistory history(UUID visit,int page,int size,boolean throughVisit) {
        var caller=SalesWorkContextService.requireTenantCaller();
        fieldwork.requireVisit(visit,"sales:risk:read");
        if(page<1||page>100000||size<1||size>50) throw new RequestValidationException("分页条件无效");
        var current=repository.capture(caller.tenantId(),visit).orElseThrow(()->new RequestValidationException("拜访不存在"));
        return repository.history(caller.tenantId(),current,fieldwork.access("sales:risk:read"),page,size,throughVisit);
    }
}
