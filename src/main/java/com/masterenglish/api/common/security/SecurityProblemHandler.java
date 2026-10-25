package com.masterenglish.api.common.security;

import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.error.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JsonMapper jsonMapper;

    public SecurityProblemHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception)
            throws IOException {
        writeProblem(request, response, ErrorCode.UNAUTHENTICATED, "Authentication is required.");
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception)
            throws IOException {
        writeProblem(request, response, ErrorCode.ACCESS_DENIED, "Access denied.");
    }

    private void writeProblem(
            HttpServletRequest request, HttpServletResponse response, ErrorCode code, String detail)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }

        Object existingId = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        String traceId = existingId == null ? UUID.randomUUID().toString() : existingId.toString();

        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(RequestIdFilter.HEADER, traceId);

        jsonMapper.writeValue(
                response.getOutputStream(),
                Map.of(
                        "type", "about:blank",
                        "title", code.getHttpStatus().getReasonPhrase(),
                        "status", code.getHttpStatus().value(),
                        "detail", detail,
                        "instance", request.getRequestURI(),
                        "code", code.name(),
                        "traceId", traceId,
                        "errors", List.of()));
    }
}
