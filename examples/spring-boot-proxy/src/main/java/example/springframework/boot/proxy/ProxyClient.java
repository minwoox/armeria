package example.springframework.boot.proxy;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpClientBuilder;
import com.linecorp.armeria.client.endpoint.EndpointGroupRegistry;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.endpoint.StaticEndpointGroup;
import org.springframework.context.annotation.Bean;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.List;

import static com.google.common.collect.ImmutableList.toImmutableList;

@Named
public class ProxyClient {

    private BackendConfiguration backendConfiguration;

    ProxyClient(BackendConfiguration backendConfiguration) {
        this.backendConfiguration = backendConfiguration;
    }

    @Bean
    HttpClient httpClient() {
        final List<Endpoint> endpoints = backendConfiguration.getPorts().stream()
                                                             .map(port -> Endpoint.of(port.getHost(), port.getPort()))
                                                             .collect(toImmutableList());
        final StaticEndpointGroup group = new StaticEndpointGroup(endpoints);
        EndpointGroupRegistry.register("backend", group, EndpointSelectionStrategy.WEIGHTED_ROUND_ROBIN);
        return new HttpClientBuilder("http://group:backend/").build();
    }

}
