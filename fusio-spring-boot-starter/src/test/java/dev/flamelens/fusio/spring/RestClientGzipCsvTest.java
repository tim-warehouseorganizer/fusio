package dev.flamelens.fusio.spring;

import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Client-side features via the same customizer the auto-configuration registers. */
class RestClientGzipCsvTest {

    private static RestClient.Builder customized() {
        RestClient.Builder builder = RestClient.builder();
        new FusioRestClientAutoConfiguration().fusioRestClientCustomizer().customize(builder);
        return builder;
    }

    @Test
    void gzipResponsesAreTransparentlyDecompressed() throws IOException {
        RestClient.Builder builder = customized();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

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

        String bodyText = builder.build().get().uri("/api/data").retrieve().body(String.class);
        assertEquals(json, bodyText);
        server.verify();
    }

    @Test
    void plainResponsesPassThroughUntouched() {
        RestClient.Builder builder = customized();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("/api/plain"))
                .andRespond(withSuccess("hello", MediaType.TEXT_PLAIN));

        assertEquals("hello", builder.build().get().uri("/api/plain").retrieve().body(String.class));
        server.verify();
    }

    @Test
    void csvBodiesSerializeOnTheWayOut() {
        RestClient.Builder builder = customized();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("/api/rows"))
                .andExpect(content().string("sku,qty\n\"A,1\",3\n"))
                .andRespond(withSuccess());

        builder.build().post().uri("/api/rows")
                .contentType(FusioCsvHttpMessageConverter.TEXT_CSV)
                .body(List.<String[]>of(new String[]{"sku", "qty"}, new String[]{"A,1", "3"}))
                .retrieve()
                .toBodilessEntity();
        server.verify();
    }

    @Test
    void csvResponsesBindToRowLists() {
        RestClient.Builder builder = customized();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("/api/rows"))
                .andRespond(withSuccess("a,b\n\"1,5\",x\n", FusioCsvHttpMessageConverter.TEXT_CSV));

        List<String[]> rows = builder.build().get().uri("/api/rows").retrieve()
                .body(new ParameterizedTypeReference<List<String[]>>() {
                });
        assertEquals(2, rows.size());
        assertArrayEquals(new String[]{"1,5", "x"}, rows.get(1));
        server.verify();
    }
}
