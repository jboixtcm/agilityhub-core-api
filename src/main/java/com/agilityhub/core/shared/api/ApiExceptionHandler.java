package com.agilityhub.core.shared.api;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.application.IcuMessageSource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final IcuMessageSource messages;
    private final RequestLocaleResolver locales;

    public ApiExceptionHandler(IcuMessageSource messages, RequestLocaleResolver locales) {
        this.messages = messages; this.locales = locales;
    }

    public ApiError body(ApiException exception, HttpServletRequest request) {
        String code = exception.code().name();
        String traceId = RequestTraceFilter.traceId(request);
        String message = messages.format("error." + code, Map.of("traceId", traceId), locales.resolveLocale(request));
        return new ApiError(code, message, exception.details(), traceId);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        LOG.error("Unhandled request exception traceId={}", RequestTraceFilter.traceId(request), exception);
        return handle(new ApiException(ErrorCode.INTERNAL_ERROR), request);
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiError> forbidden(HttpServletRequest request) {
        return handle(new ApiException(ErrorCode.FORBIDDEN), request);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handle(ApiException exception, HttpServletRequest request) {
        var response = ResponseEntity.status(exception.code().httpStatus());
        if (exception.details().get("retryAfter") instanceof Number retry) { response.header("Retry-After", retry.toString()); }
        return response.body(body(exception, request));
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<ApiError> notImplemented(HttpServletRequest request) {
        return handle(new ApiException(ErrorCode.NOT_IMPLEMENTED), request);
    }

    @ExceptionHandler({org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.bind.MissingRequestHeaderException.class})
    public ResponseEntity<ApiError> missingParameter(HttpServletRequest request) {
        return handle(new ApiException(ErrorCode.VALIDATION_ERROR), request);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiError> validation(BindException exception, HttpServletRequest request) {
        var errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), error.getCode(), error.getDefaultMessage())).toList();
        return handle(new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("fieldErrors", errors)), request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> validation(ConstraintViolationException exception, HttpServletRequest request) {
        var errors = exception.getConstraintViolations().stream().map(violation -> new FieldError(
                violation.getPropertyPath().toString(),
                violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName(),
                violation.getMessage())).toList();
        return handle(new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("fieldErrors", errors)), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> malformed(HttpServletRequest request) {
        return handle(new ApiException(ErrorCode.VALIDATION_ERROR), request);
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiError> notFound(HttpServletRequest request) {
        return handle(new ApiException(ErrorCode.NOT_FOUND), request);
    }

    public record FieldError(String field, String code, String message) { }
}
