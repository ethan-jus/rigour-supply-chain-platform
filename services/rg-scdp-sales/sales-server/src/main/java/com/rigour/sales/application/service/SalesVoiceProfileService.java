package com.rigour.sales.application.service;

import com.rigour.sales.api.v1.SalesVoiceProfileApi.Profile;
import com.rigour.sales.application.port.out.SalesVoiceEnrollmentClient;
import com.rigour.sales.application.port.out.SalesVoiceProfileRepository;
import com.rigour.sales.domain.VoiceIdentity;
import com.rigour.shared.audit.*;
import com.rigour.shared.context.*;
import com.rigour.shared.core.exception.RequestValidationException;
import java.time.*;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class SalesVoiceProfileService {
    private final SalesWorkContextService context;
    private final SalesEmployeeProfileService employees;
    private final SalesVoiceProfileRepository profiles;
    private final ObjectProvider<SalesVoiceEnrollmentClient> speech;
    private final AuditSink audit;
    private final Clock clock;
    public SalesVoiceProfileService(SalesWorkContextService context,SalesEmployeeProfileService employees,
            SalesVoiceProfileRepository profiles,ObjectProvider<SalesVoiceEnrollmentClient> speech,AuditSink audit,Clock clock) {
        this.context=context;this.employees=employees;this.profiles=profiles;this.speech=speech;this.audit=audit;this.clock=clock;
    }
    public Profile read() {return change(null,0,false,false);}
    public Profile register(byte[] audio,long revision,boolean consent) {
        if(!consent)throw new RequestValidationException("请确认声音登记用途后继续");
        if(audio==null||audio.length==0||audio.length>2_097_152)throw new RequestValidationException("请录制 20–30 秒清晰的本人声音");
        return change(audio,revision,true,false);
    }
    public Profile delete(long revision) {return change(null,revision,true,true);}
    private Profile change(byte[] audio,long revision,boolean write,boolean delete) {
        var caller=SalesWorkContextService.requireTenantCaller();
        AuthorizationContext.requirePermission(write?"sales:recording:own:write":"sales:context:read");
        var identity=context.resolveIdentity(caller,clock.instant());
        String name=employees.profile().employeeName();
        var id=identity.profile().id();
        if(!write)return profiles.read(caller.tenantId(),id,name);
        Profile result;
        if(delete)result=profiles.delete(caller.tenantId(),id,name,revision,clock.instant());
        else {
            if(profiles.read(caller.tenantId(),id,name).revision()!=revision)throw new RequestValidationException("声音登记已变化，请刷新后重试");
            var client=speech.getIfAvailable();if(client==null)throw new RequestValidationException("声音识别服务暂不可用，请稍后重试");
            SalesVoiceEnrollmentClient.Enrollment voice;
            try{voice=client.enroll(audio);}catch(com.rigour.sales.application.port.out.SalesAudioAiClient.AiClientException e){
                throw new RequestValidationException("VOICE_QUALITY".equals(e.code())?"录音不够清晰或有其他人声，请在安静处重新录制":"声音识别服务繁忙，请稍后重试");
            }
            if(!VoiceIdentity.valid(voice.embedding())||voice.speechMs()<8000)throw new RequestValidationException("请在安静处录制更清晰的本人声音");
            result=profiles.save(caller.tenantId(),id,name,revision,voice,clock.instant());
        }
        audit.append(new AuditEvent(caller.tenantId().toString(),RequestContext.getRequestId(),caller.userId().toString(),
                delete?"SALES_VOICE_DELETED":"SALES_VOICE_REGISTERED","SALES_PROFILE",id.toString(),
                Map.of("revision",Long.toString(result.revision())),OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC)));
        return result;
    }
}
