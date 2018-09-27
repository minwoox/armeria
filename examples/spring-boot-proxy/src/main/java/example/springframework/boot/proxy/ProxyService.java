package example.springframework.boot.proxy;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;

import javax.inject.Inject;
import javax.inject.Named;

@Named
public class ProxyService {

    private final HttpClient httpClient;

    ProxyService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Get("/")
    public HttpResponse home() {
        return HttpResponse.of(HttpStatus.OK);
    }

    @Get("/hello/{name}")
    public HttpResponse hello(@Param("name") String name) {
        return HttpResponse.of("Hello, %s!", name);
    }
}
