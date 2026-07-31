package com.rigour.tenant.iam.application.service.portal;

import com.rigour.tenant.iam.application.port.out.PortalAccessReader;
import java.util.List;
import java.util.Objects;

/** Portal访问用例；授权计算留在IAM，前端只消费结果。 */
public final class PortalAccessService {

    private final PortalAccessReader reader;

    public PortalAccessService(PortalAccessReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader cannot be null");
    }

    public PortalCurrentUser currentUser(PortalAccessQuery query) {
        return reader.readCurrentUser(query);
    }

    public List<PortalApplication> grantedApplications(PortalAccessQuery query) {
        return List.copyOf(reader.readGrantedApplications(query));
    }
}
