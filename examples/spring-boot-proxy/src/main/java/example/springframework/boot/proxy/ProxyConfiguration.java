package example.springframework.boot.proxy;

import com.google.common.collect.ImmutableList;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.spring.AnnotatedServiceRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProxyConfiguration {

    @Bean
    public AnnotatedServiceRegistrationBean proxy(ProxyService proxyService) {
        return new AnnotatedServiceRegistrationBean()
                .setServiceName("proxyService")
                .setPathPrefix("/")
                .setService(proxyService)
                .setDecorators(ImmutableList.of(LoggingService.newDecorator()));
    }
}
