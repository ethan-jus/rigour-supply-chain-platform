package com.rigour.sales.application.port.out;

import com.rigour.sales.api.v1.SalesVoiceProfileApi.Profile;
import java.time.Instant;
import java.util.UUID;

public interface SalesVoiceProfileRepository {
    Profile read(UUID tenant,UUID profile,String name);
    Profile save(UUID tenant,UUID profile,String name,long revision,SalesVoiceEnrollmentClient.Enrollment voice,Instant now);
    Profile delete(UUID tenant,UUID profile,String name,long revision,Instant now);
}
