package dev.flamelens.fusio.spring;

import dev.flamelens.fusio.interop.PipeInputStream;
import dev.flamelens.fusio.pipes.Gzip;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.io.InputStream;

/**
 * Client-side transparent gzip: advertises {@code Accept-Encoding: gzip} on
 * outgoing requests (unless the caller set their own) and decompresses
 * {@code Content-Encoding: gzip} responses through fusio's streaming gunzip
 * stage. Notably useful with the JDK HttpClient-backed RestClient, which does
 * NOT decompress responses on its own.
 */
public class FusioGzipResponseInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        if (request.getHeaders().getFirst(HttpHeaders.ACCEPT_ENCODING) == null) {
            request.getHeaders().set(HttpHeaders.ACCEPT_ENCODING, "gzip");
        }
        ClientHttpResponse response = execution.execute(request, body);
        String encoding = response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING);
        if (encoding != null && encoding.trim().equalsIgnoreCase("gzip")) {
            return new GunzippedResponse(response);
        }
        return response;
    }

    private static final class GunzippedResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final HttpHeaders headers;
        private InputStream body;

        GunzippedResponse(ClientHttpResponse delegate) {
            this.delegate = delegate;
            HttpHeaders masked = new HttpHeaders();
            masked.putAll(delegate.getHeaders());
            masked.remove(HttpHeaders.CONTENT_ENCODING);
            masked.remove(HttpHeaders.CONTENT_LENGTH); // decompressed length is unknown
            this.headers = HttpHeaders.readOnlyHttpHeaders(masked);
        }

        @Override
        public InputStream getBody() throws IOException {
            if (body == null) {
                body = new PipeInputStream(delegate.getBody(), Gzip.gunzip());
            }
            return body;
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
