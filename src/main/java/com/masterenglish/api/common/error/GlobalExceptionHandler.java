package com.masterenglish.api.common.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> handleAccessDenied(AccessDeniedException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem(HttpStatus.FORBIDDEN,
                ErrorCode.ACCESS_DENIED, "Access denied.", request, List.of()));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> handleUnauthenticated(AuthenticationException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem(HttpStatus.UNAUTHORIZED,
                ErrorCode.UNAUTHENTICATED, "Authentication is required.", request, List.of()));
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<Object> handleBusiness(BusinessException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.getErrorCode().getHttpStatus()).body(problem(
                exception.getErrorCode().getHttpStatus(), exception.getErrorCode(),
                exception.getMessage(), request, List.of()));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = exception.getBindingResult().getAllErrors().stream()
                .map(error -> new FieldViolation(
                        error instanceof org.springframework.validation.FieldError field
                                ? field.getField() : "_request",
                        error.getDefaultMessage()))
                .toList();
        return new ResponseEntity<>(problem(status, ErrorCode.VALIDATION_ERROR,
                "Request validation failed.", servlet(request), errors), headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return new ResponseEntity<>(problem(status, ErrorCode.VALIDATION_ERROR,
                "Malformed JSON request.", servlet(request), List.of()), headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ErrorCode code = switch (status.value()) {
            case 401 -> ErrorCode.UNAUTHENTICATED;
            case 403 -> ErrorCode.ACCESS_DENIED;
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
            case 429 -> ErrorCode.RATE_LIMITED;
            default -> status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.VALIDATION_ERROR;
        };
        String detail = status.is5xxServerError() ? "An unexpected error occurred."
                : "The request could not be processed.";
        return new ResponseEntity<>(problem(status, code, detail, servlet(request), List.of()), headers, status);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception exception, HttpServletRequest request) {
        // Do not log exception messages or rejected input that may contain secrets.
        LOG.error("Unhandled request error: type={}, traceId={}",
                exception.getClass().getName(), request.getAttribute(RequestIdFilter.ATTRIBUTE));
        return ResponseEntity.internalServerError().body(problem(HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", request, List.of()));
    }

    private static ProblemDetail problem(HttpStatusCode status, ErrorCode code, String detail,
            HttpServletRequest request, List<FieldViolation> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code.name());
        Object traceId = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        problem.setProperty("traceId", traceId == null ? UUID.randomUUID().toString() : traceId.toString());
        problem.setProperty("errors", errors);
        return problem;
    }

    private static HttpServletRequest servlet(WebRequest request) {
        return ((ServletWebRequest) request).getRequest();
    }
}
