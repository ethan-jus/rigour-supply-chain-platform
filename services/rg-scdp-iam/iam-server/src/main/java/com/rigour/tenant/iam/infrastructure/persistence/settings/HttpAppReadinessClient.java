package com.rigour.tenant.iam.infrastructure.persistence.settings;
import com.rigour.shared.context.*;
import com.rigour.tenant.iam.application.port.out.AppReadinessClient;
import com.rigour.tenant.iam.application.service.settings.AppCutoverModels.*;
import com.rigour.tenant.iam.api.v1.model.SupplyReadinessView;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.net.*;import java.net.http.HttpClient;import java.time.Duration;import java.util.*;
/** 超时或缺失服务均返回阻塞项，禁止通过旧部署误开启新授权。 */
@Component
public final class HttpAppReadinessClient implements AppReadinessClient {
 private final RestClient client;private final TrustedContextSigner signer;private final Map<String,URI> endpoints=new TreeMap<>();
 public HttpAppReadinessClient(TrustedContextSigner signer,Environment environment,RestClient.Builder restClientBuilder){
 this.signer=signer;var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());factory.setReadTimeout(Duration.ofSeconds(5));client=restClientBuilder.requestFactory(factory).build();
 String[][] sources={{"hr","HR_PAYROLL_BASE_URL","rigour-hr-payroll-service"},{"crm","MERCHANT_CRM_BASE_URL","rigour-merchant-crm-service"},{"erp","ERP_CORE_BASE_URL","rigour-erp-core-service"},{"order","ORDER_CENTER_BASE_URL","rigour-order-center-service"},{"bi","ANALYTICS_BI_BASE_URL","rigour-analytics-bi-service"},{"settings","BUSINESS_SETTINGS_BASE_URL","rigour-business-settings-service"}};
 for(var s:sources){String base=environment.getProperty("rigour.iam."+s[0]+"-base-url",environment.getProperty(s[1],"http://"+s[2]));URI uri=URI.create(base.replaceAll("/+$","")+"/internal/v1/supply/readiness");if(!Set.of("http","https").contains(uri.getScheme())||uri.getUserInfo()!=null)throw new IllegalArgumentException("准备检查服务地址无效");endpoints.put(s[0],uri);}
 }
 public List<Domain> inspect(UUID tenant){var result=new ArrayList<Domain>();for(var entry:endpoints.entrySet()){try{result.add(read(tenant,entry.getKey(),entry.getValue()));}catch(RuntimeException ex){result.add(new Domain(entry.getKey(),"UNAVAILABLE",List.of(new Issue("SERVICE_"+entry.getKey(),"BLOCKING",1,"无法核验 "+entry.getKey()+" 服务，请检查部署版本和服务连接"))));}}return result;}
 private Domain read(UUID tenant,String domain,URI uri){
        Map<String, String> h = new LinkedHashMap<>();
        h.put(RequestHeaders.PRINCIPAL_SCOPE, "SERVICE");
        h.put(
                RequestHeaders.PRINCIPAL_ID,
                UUID.nameUUIDFromBytes(
                                "rigour-iam-scope-reader"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .toString());
        h.put(RequestHeaders.TENANT_ID, tenant.toString());
        h.put(RequestHeaders.SESSION_ID, UUID.randomUUID().toString());
        h.put(RequestHeaders.SESSION_VERSION, "0");
        h.put(RequestHeaders.USER_SECURITY_VERSION, "0");
        h.put(RequestHeaders.TENANT_POLICY_VERSION, "0");
        h.put(RequestHeaders.PERMISSIONS, "supply:readiness:read");
        var signed = signer.sign("GET", uri.getRawPath(), uri.getRawQuery(), h);
        h.put(RequestHeaders.CONTEXT_KEY_ID, signed.keyId());
        h.put(RequestHeaders.CONTEXT_TIMESTAMP, signed.timestamp());
        h.put(RequestHeaders.CONTEXT_SIGNATURE, signed.signature());

 var response=client.get().uri(uri).headers(v->h.forEach(v::set)).retrieve().body(SupplyReadinessView.class);
 if(response==null||response.contractVersion()!=1||!domain.equals(response.domain())||response.version()==null||response.checks()==null)throw new IllegalStateException("服务检查契约无效");
 return new Domain(domain,response.version(),response.checks().stream().map(c->new Issue(domain+":"+c.code(),c.severity(),c.count(),c.message())).toList());
 }
}
