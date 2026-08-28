package dev.flamelens.fusio.spring;

import dev.flamelens.fusio.interop.PipeInputStream;
import dev.flamelens.fusio.pipes.Gzip;
import javax.servlet.FilterChain;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Transparently decompresses {@code Content-Encoding: gzip} request bodies so
 * downstream consumers (Jackson, the CSV converter, application code) see
 * plaintext. Servlet containers compress responses but do not decompress
 * request bodies; without this, apps hand-wrap request streams in
 * GZIPInputStream decorators. Decompression streams through fusio's
 * {@code Gzip.gunzip()} stage lazily — nothing is buffered beyond one chunk.
 *
 * Content-Encoding and Content-Length headers are masked on the wrapped
 * request (the decompressed length is unknown), matching what downstream
 * framework code expects.
 */
public class FusioRequestDecompressionFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String encoding = request.getHeader("Content-Encoding");
        if (encoding != null && encoding.trim().equalsIgnoreCase("gzip")) {
            chain.doFilter(new GunzippedRequest(request), response);
        } else {
            chain.doFilter(request, response);
        }
    }

    private static final class GunzippedRequest extends HttpServletRequestWrapper {
        private ServletInputStream stream;
        private BufferedReader reader;

        GunzippedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new PlainServletInputStream(
                        new PipeInputStream(getRequest().getInputStream(), Gzip.gunzip()));
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (reader == null) {
                String enc = getCharacterEncoding();
                Charset charset = enc != null ? Charset.forName(enc) : StandardCharsets.UTF_8;
                reader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
            }
            return reader;
        }

        @Override
        public int getContentLength() {
            return -1;
        }

        @Override
        public long getContentLengthLong() {
            return -1;
        }

        @Override
        public String getHeader(String name) {
            return masked(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return masked(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames()).stream()
                    .filter(n -> !masked(n))
                    .collect(java.util.stream.Collectors.toList());
            return Collections.enumeration(names);
        }

        private static boolean masked(String name) {
            return "Content-Encoding".equalsIgnoreCase(name)
                    || "Content-Length".equalsIgnoreCase(name);
        }
    }

    private static final class PlainServletInputStream extends ServletInputStream {
        private final InputStream delegate;
        private boolean finished = false;

        PlainServletInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b == -1) {
                finished = true;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            if (n == -1) {
                finished = true;
            }
            return n;
        }

        @Override
        public boolean isFinished() {
            return finished;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException("async reads are not supported");
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
