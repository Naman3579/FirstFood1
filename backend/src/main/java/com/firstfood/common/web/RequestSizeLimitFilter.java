package com.firstfood.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects oversized request bodies before any parsing or authentication work happens.
 *
 * A declared {@code Content-Length} over the limit gets an immediate 413. A body with no declared length (chunked)
 * is counted as it is read and aborted with {@link RequestBodyTooLargeException} once it passes the limit, which
 * {@code GlobalExceptionHandler} turns into the same 413.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;

    public RequestSizeLimitFilter(HttpLimitsProperties properties) {
        this.maxBytes = properties.maxRequestBodyBytes();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            response.setStatus(413);
            response.setHeader("Cache-Control", "no-store");
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"code\":\"PAYLOAD_TOO_LARGE\",\"message\":\"Request body is too large.\","
                    + "\"timestamp\":\"" + java.time.Instant.now() + "\"}");
            return;
        }
        chain.doFilter(new LimitedRequest(request, maxBytes), response);
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private final long maxBytes;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedStream(super.getInputStream(), maxBytes);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class LimitedStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long maxBytes;
        private long read;

        LimitedStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            int n = delegate.read(buffer, off, len);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws IOException {
            read += n;
            if (read > maxBytes) {
                throw new RequestBodyTooLargeException(maxBytes);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
