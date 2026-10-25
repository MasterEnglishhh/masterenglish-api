package com.masterenglish.api.common.web;

import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.error.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

// Keep final servlet lifecycle methods out of module-observability proxies.
@Component
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class JsonRequestSizeFilter extends OncePerRequestFilter {

    private static final int MAX_JSON_BYTES = 1024 * 1024;

    private final JsonMapper jsonMapper;

    public JsonRequestSizeFilter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String contentType = request.getContentType();

        if (contentType == null) {
            return true;
        }

        String type = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);

        return !type.equals(MediaType.APPLICATION_JSON_VALUE) && !type.endsWith("+json");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (request.getContentLengthLong() > MAX_JSON_BYTES) {
            reject(request, response);
            return;
        }

        // Check actual bytes, including requests without Content-Length.
        byte[] body = request.getInputStream().readNBytes(MAX_JSON_BYTES + 1);

        if (body.length > MAX_JSON_BYTES) {
            reject(request, response);
            return;
        }

        // Let Spring MVC read the body after the size check.
        filterChain.doFilter(new CachedJsonRequest(request, body), response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response)
            throws IOException {

        Object existingId = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        String traceId = existingId == null ? UUID.randomUUID().toString() : existingId.toString();

        response.setStatus(ErrorCode.PAYLOAD_TOO_LARGE.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(RequestIdFilter.HEADER, traceId);

        jsonMapper.writeValue(
                response.getOutputStream(),
                Map.of(
                        "type",
                        "about:blank",
                        "title",
                        "Content Too Large",
                        "status",
                        413,
                        "detail",
                        "JSON request body must not exceed 1 MiB.",
                        "instance",
                        request.getRequestURI(),
                        "code",
                        ErrorCode.PAYLOAD_TOO_LARGE.name(),
                        "traceId",
                        traceId,
                        "errors",
                        List.of()));
    }

    private static final class CachedJsonRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedJsonRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);

            return new ServletInputStream() {
                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    return input.read(buffer, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    // This wrapper is for blocking Spring MVC JSON reads.
                    throw new IllegalStateException("Non-blocking body reads are not supported.");
                }
            };
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();

            return new BufferedReader(
                    new InputStreamReader(
                            getInputStream(),
                            encoding == null ? StandardCharsets.UTF_8.name() : encoding));
        }
    }
}
