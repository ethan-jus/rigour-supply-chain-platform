package com.rigour.platform.http;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.support.HttpRequestWrapper;

/**
 * 出站 HTTP 的服务名寻址：请求主机名命中注册中心服务名时改写为实例地址，
 * 由 Spring Cloud LoadBalancer 选择实例；外部域名、localhost、直连 IP 原样放行。
 * 这样服务间调用不再配置对端 IP，且保留本地临时指向某个实例的逃生舱。
 */
public final class ServiceAddressResolver implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ServiceAddressResolver.class);

    private final ObjectProvider<DiscoveryClient> discoveryClient;
    private final ObjectProvider<LoadBalancerClient> loadBalancerClient;

    public ServiceAddressResolver(
            ObjectProvider<DiscoveryClient> discoveryClient,
            ObjectProvider<LoadBalancerClient> loadBalancerClient) {
        this.discoveryClient = discoveryClient;
        this.loadBalancerClient = loadBalancerClient;
    }

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        URI resolved = resolve(request.getURI());
        if (resolved == null) {
            return execution.execute(request, body);
        }
        return execution.execute(new ResolvedUriRequest(request, resolved), body);
    }

    private URI resolve(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank() || !isHttp(uri)) {
            return null;
        }
        DiscoveryClient discovery = discoveryClient.getIfAvailable();
        if (discovery == null) {
            return null;
        }
        String serviceName = host.strip();
        List<ServiceInstance> instances = discovery.getInstances(serviceName);
        if (instances.isEmpty()) {
            return null;
        }
        ServiceInstance instance = choose(serviceName);
        if (instance == null) {
            instance = instances.get(0);
        }
        URI target = instance.getUri();
        if (log.isDebugEnabled()) {
            log.debug(
                    "服务名寻址 service={} -> {}:{}", serviceName, target.getHost(), target.getPort());
        }
        return rewrite(target, uri);
    }

    private ServiceInstance choose(String serviceName) {
        LoadBalancerClient loadBalancer = loadBalancerClient.getIfAvailable();
        if (loadBalancer == null) {
            return null;
        }
        try {
            return loadBalancer.choose(serviceName);
        } catch (RuntimeException exception) {
            log.warn("服务名负载均衡选择失败 service={} reason={}", serviceName, exception.toString());
            return null;
        }
    }

    private static boolean isHttp(URI uri) {
        String scheme = uri.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private static URI rewrite(URI target, URI original) {
        try {
            return new URI(
                    target.getScheme(),
                    null,
                    target.getHost(),
                    target.getPort(),
                    original.getRawPath(),
                    original.getRawQuery(),
                    original.getRawFragment());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("服务实例地址无法用于请求改写: " + target, exception);
        }
    }

    private static final class ResolvedUriRequest extends HttpRequestWrapper {
        private final URI uri;

        ResolvedUriRequest(HttpRequest request, URI uri) {
            super(request);
            this.uri = uri;
        }

        @Override
        public URI getURI() {
            return uri;
        }
    }
}
