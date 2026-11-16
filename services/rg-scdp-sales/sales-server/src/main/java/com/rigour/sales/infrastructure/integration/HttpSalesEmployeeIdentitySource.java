package com.rigour.sales.infrastructure.integration;
import com.rigour.sales.application.port.out.SalesEmployeeIdentitySource;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TrustedContextSigner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
@Component
public final class HttpSalesEmployeeIdentitySource implements SalesEmployeeIdentitySource {
    private final SalesIdentityGateway gateway;
    @Autowired
    public HttpSalesEmployeeIdentitySource(SalesIdentityGateway gateway) { this.gateway=gateway; }
    public HttpSalesEmployeeIdentitySource(TrustedContextSigner signer,RestClient.Builder builder,String iam,String hr) {
        this(new SalesIdentityGateway(signer,builder,iam,hr));
    }
    @Override public Employee current(CallerIdentity caller) { return gateway.current(caller); }
}
