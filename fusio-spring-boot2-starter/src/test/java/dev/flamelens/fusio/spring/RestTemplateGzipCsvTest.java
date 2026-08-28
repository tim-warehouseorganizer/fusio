package dev.flamelens.fusio.spring;

import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Client-side features via the same customizer the auto-configuration registers (Boot 2.x: RestTemplate). */
class RestTemplateGzipCsvTest {

    private static RestTemplate customized() {
        RestTemplate template = new RestTemplate();
        new FusioRestTemplateAutoConfiguration().fusioRestTemplateCustomizer().customize(template);
        return template;
    }

    @Test
    void gzipResponsesAreTransparentlyDecompressed() throws IOException {
        RestTemplate template = customized();
        MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();

        String json = "{\"status\":\"ok\",\"items\":123}";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(json.getBytes(StandardCharsets.UTF_8));
        }
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.set(HttpHeaders.CONTENT_ENCODING, "gzip");
        server.expect(requestTo("/api/data"))
                .andExpect(header(HttpHeaders.ACCEPT_ENCODING, "gzip"))
                .andRespond(withSuccess(bos.toByteArray(), MediaType.APPLICATION_JSON)
                        .headers(responseHeaders));

        String bodyText = template.getForObject("/api/data", String.class);
        assertEquals(json, bodyText);
        server.verify();
    }

    @Test
    void plainResponsesPassThroughUntouched() {
        RestTemplate template = customized();
        MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
        server.expect(requestTo("/api/plain"))
                .andRespond(withSuccess("hello", MediaType.TEXT_PLAIN));

        assertEquals("hello", template.getForObject("/api/plain", String.class));
        server.verify();
    }

    @Test
    void csvBodiesSerializeOnTheWayOut() {
        RestTemplate template = customized();
        MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
        server.expect(requestTo("/api/rows"))
                .andExpect(content().string("sku,qty\n\"A,1\",3\n"))
                .andRespond(withSuccess());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(FusioCsvHttpMessageConverter.TEXT_CSV);
        List<String[]> rows = Arrays.asList(new String[]{"sku", "qty"}, new String[]{"A,1", "3"});
        template.exchange("/api/rows", HttpMethod.POST, new HttpEntity<>(rows, headers), Void.class);
        server.verify();
    }

    @Test
    void csvResponsesBindToRowLists() {
        RestTemplate template = customized();
        MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
        server.expect(requestTo("/api/rows"))
                .andRespond(withSuccess("a,b\n\"1,5\",x\n", FusioCsvHttpMessageConverter.TEXT_CSV));

        List<String[]> rows = template.exchange("/api/rows", HttpMethod.GET, null,
                new ParameterizedTypeReference<List<String[]>>() {
                }).getBody();
        assertEquals(2, rows.size());
        assertArrayEquals(new String[]{"1,5", "x"}, rows.get(1));
        server.verify();
    }
}
