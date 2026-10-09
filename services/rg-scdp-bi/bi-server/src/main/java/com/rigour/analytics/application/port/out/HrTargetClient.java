package com.rigour.analytics.application.port.out;

import com.rigour.hr.api.v1.model.TargetSettingsModels.Target;

import java.util.List;

public interface HrTargetClient {
    List<Target> values(String tenant, String from, String to);
}
