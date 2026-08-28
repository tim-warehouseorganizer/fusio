package dev.flamelens.fusio.spring;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/**
 * Client-side auto-configuration, active when RestClient support is on the
 * classpath: every Boot-built {@link RestClient.Builder} gets transparent
 * gzip response decompression and the {@code text/csv} converter, so
 * service-to-service calls speak the same dialects the server side does.
 * Disable with {@code fusio.rest-client=false}.
 */
@AutoConfiguration
@ConditionalOnClass({RestClient.class, RestClientCustomizer.class})
@ConditionalOnProperty(prefix = "fusio", name = "rest-client", havingValue = "true", matchIfMissing = true)
public class FusioRestClientAutoConfiguration {

    @Bean
    public RestClientCustomizer fusioRestClientCustomizer() {
        FusioCsvHttpMessageConverter csv = new FusioCsvHttpMessageConverter();
        FusioGzipResponseInterceptor gzip = new FusioGzipResponseInterceptor();
        return builder -> builder
                .requestInterceptor(gzip)
                .messageConverters(converters -> converters.add(csv));
    }
}
