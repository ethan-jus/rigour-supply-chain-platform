package com.rigour.platform.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

/** 出站服务名寻址只处理内部服务名，注册中心异常不能阻断外部请求。 */
class ServiceAddressResolverTest {

    @Test
    void externalHostAndIpArePassedThroughWithoutDiscoveryLookup() throws IOException {
        RecordingDiscovery discovery = new RecordingDiscovery(List.of());
        ServiceAddressResolver resolver = new ServiceAddressResolver(provider(discovery), provider(null));

        URI external = execute(resolver, "https://api.dinghuobao.com/order/list");
        URI ip = execute(resolver, "http://10.0.0.9:8080/health");
        URI local = execute(resolver, "http://localhost:26880/actuator/health");

        assertThat(external.getHost()).isEqualTo("api.dinghuobao.com");
        assertThat(ip.getHost()).isEqualTo("10.0.0.9");
        assertThat(local.getHost()).isEqualTo("localhost");
        assertThat(discovery.lookups).isZero();
    }

    @Test
    void internalServiceNameIsResolvedFromDiscovery() throws IOException {
        RecordingDiscovery discovery =
                new RecordingDiscovery(List.of(instance("http://10.0.0.5:26885")));
        ServiceAddressResolver resolver = new ServiceAddressResolver(provider(discovery), provider(null));

        URI resolved = execute(resolver, "http://rigour-order-center-service/api/v1/orders");

        assertThat(discovery.lookups).isEqualTo(1);
        assertThat(resolved.getHost()).isEqualTo("10.0.0.5");
        assertThat(resolved.getPort()).isEqualTo(26885);
        assertThat(resolved.getPath()).isEqualTo("/api/v1/orders");
    }

    @Test
    void discoveryFailureFallsBackToOriginalAddress() throws IOException {
        RecordingDiscovery discovery = new RecordingDiscovery(List.of());
        discovery.failure = new IllegalStateException("nacos down");
        ServiceAddressResolver resolver = new ServiceAddressResolver(provider(discovery), provider(null));

        URI resolved = execute(resolver, "http://rigour-order-center-service/api/v1/orders");

        assertThat(resolved.getHost()).isEqualTo("rigour-order-center-service");
        assertThat(discovery.lookups).isEqualTo(1);
    }

    private static URI execute(ServiceAddressResolver resolver, String url) throws IOException {
        HttpRequest request = request(url);
        ClientHttpRequestExecution execution =
                (input, body) -> new EmptyClientHttpResponse(input.getURI());
        try (ClientHttpResponse response = resolver.intercept(request, new byte[0], execution)) {
            return ((EmptyClientHttpResponse) response).uri();
        }
    }

    private static HttpRequest request(String url) {
        URI uri = URI.create(url);
        return new HttpRequest() {
            @Override
            public org.springframework.http.HttpMethod getMethod() {
                return org.springframework.http.HttpMethod.GET;
            }

            @Override
            public URI getURI() {
                return uri;
            }

            @Override
            public HttpHeaders getHeaders() {
                return new HttpHeaders();
            }

            @Override
            public java.util.Map<String, Object> getAttributes() {
                return java.util.Map.of();
            }
        };
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }
        };
    }

    private static ServiceInstance instance(String uri) {
        URI target = URI.create(uri);
        return new ServiceInstance() {
            @Override
            public String getInstanceId() {
                return "instance-1";
            }

            @Override
            public String getServiceId() {
                return "rigour-order-center-service";
            }

            @Override
            public String getHost() {
                return target.getHost();
            }

            @Override
            public int getPort() {
                return target.getPort();
            }

            @Override
            public boolean isSecure() {
                return false;
            }

            @Override
            public URI getUri() {
                return target;
            }

            @Override
            public java.util.Map<String, String> getMetadata() {
                return java.util.Map.of();
            }
        };
    }

    private static final class RecordingDiscovery implements DiscoveryClient {
        private final List<ServiceInstance> instances;
        private int lookups;
        private RuntimeException failure;

        private RecordingDiscovery(List<ServiceInstance> instances) {
            this.instances = instances;
        }

        @Override
        public String description() {
            return "test";
        }

        @Override
        public List<ServiceInstance> getInstances(String serviceId) {
            lookups += 1;
            if (failure != null) throw failure;
            return instances;
        }

        @Override
        public List<String> getServices() {
            return List.of();
        }
    }

    private record EmptyClientHttpResponse(URI uri) implements ClientHttpResponse {
        @Override
        public HttpStatusCode getStatusCode() {
            return HttpStatus.OK;
        }

        @Override
        public String getStatusText() {
            return "OK";
        }

        @Override
        public void close() {}

        @Override
        public java.io.InputStream getBody() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public HttpHeaders getHeaders() {
            return new HttpHeaders();
        }
    }
}
