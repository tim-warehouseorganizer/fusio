package dev.flamelens.fusio.spring;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Auto-configuration: add the jar, get behavior. Registers
 * <ul>
 *   <li>{@link FusioCsvHttpMessageConverter} — controllers can consume and
 *       produce {@code text/csv} with {@code List<String[]>} bodies (Boot
 *       picks up any HttpMessageConverter bean additively);</li>
 *   <li>{@link FusioRequestDecompressionFilter} — gzip request bodies are
 *       transparently decompressed for every downstream consumer. Disable
 *       with {@code fusio.request-decompression=false}.</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class FusioAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FusioCsvHttpMessageConverter fusioCsvHttpMessageConverter() {
        return new FusioCsvHttpMessageConverter();
    }

    @Bean
    @ConditionalOnProperty(prefix = "fusio", name = "request-decompression",
            havingValue = "true", matchIfMissing = true)
    public FilterRegistrationBean<FusioRequestDecompressionFilter> fusioRequestDecompressionFilter() {
        FilterRegistrationBean<FusioRequestDecompressionFilter> registration =
                new FilterRegistrationBean<>(new FusioRequestDecompressionFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
