package dev.flamelens.fusio.spring;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

/**
 * Client-side auto-configuration for Spring Boot 2.x, where the HTTP client
 * is {@link RestTemplate} (RestClient only exists from Boot 3.2). Every
 * {@code RestTemplateBuilder}-built template gets transparent gzip response
 * decompression and the {@code text/csv} converter, so service-to-service
 * calls speak the same dialects the server side does. Disable with
 * {@code fusio.rest-client=false} (same property as the Boot 3/4 starters).
 */
@AutoConfiguration
@ConditionalOnClass({RestTemplate.class, RestTemplateCustomizer.class})
@ConditionalOnProperty(prefix = "fusio", name = "rest-client", havingValue = "true", matchIfMissing = true)
public class FusioRestTemplateAutoConfiguration {

    @Bean
    public RestTemplateCustomizer fusioRestTemplateCustomizer() {
        FusioCsvHttpMessageConverter csv = new FusioCsvHttpMessageConverter();
        FusioGzipResponseInterceptor gzip = new FusioGzipResponseInterceptor();
        return restTemplate -> {
            restTemplate.getInterceptors().add(gzip);
            restTemplate.getMessageConverters().add(csv);
        };
    }
}
