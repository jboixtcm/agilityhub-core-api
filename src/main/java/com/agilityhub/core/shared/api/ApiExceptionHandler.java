package com.agilityhub.core.shared.api;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.application.IcuMessageSource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final IcuMessageSource messages;
    private final RequestLocaleResolver locales;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.sentry.IScopes sentry;

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
    public ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request,
                                               jakarta.servlet.http.HttpServletResponse response) {
        if (exception instanceof org.springframework.web.context.request.async.AsyncRequestNotUsableException
                || org.springframework.web.util.DisconnectedClientHelper.isClientDisconnectedException(exception)) {
            // The client went away (a download or an SSE stream): nothing can be written and nothing failed on our side.
            LOG.debug("Async response no longer usable traceId={}", RequestTraceFilter.traceId(request));
            return null;
        }
        if (exception instanceof org.springframework.web.context.request.async.AsyncRequestTimeoutException
                && response.isCommitted()) {
            // An SSE stream already sent events: a JSON error cannot follow them (as Spring's own handler does).
            LOG.debug("Async request timed out after the response was committed traceId={}", RequestTraceFilter.traceId(request));
            return null;
        }
        if (exception instanceof ErrorResponse framework) {
            int status = framework.getStatusCode().value();
            ErrorCode code = switch (status) {
                case 400 -> ErrorCode.VALIDATION_ERROR;
                case 401 -> ErrorCode.UNAUTHENTICATED;
                case 403 -> ErrorCode.FORBIDDEN;
                case 404 -> ErrorCode.NOT_FOUND;
                case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
                case 406 -> ErrorCode.NOT_ACCEPTABLE;
                case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
                case 429 -> ErrorCode.RATE_LIMITED;
                default -> status < 500 ? ErrorCode.VALIDATION_ERROR : ErrorCode.INTERNAL_ERROR;
            };
            if (status < 500 || exception instanceof org.springframework.web.context.request.async.AsyncRequestTimeoutException) {
                // An expired async request (SSE) is an expected end of the stream, not an internal error.
                LOG.warn("Framework request rejected status={} code={} traceId={}", status, code,
                        RequestTraceFilter.traceId(request));
            } else {
                captureInternalError(exception, request);
                LOG.error("Unhandled request exception traceId={}", RequestTraceFilter.traceId(request), exception);
            }
            return ResponseEntity.status(framework.getStatusCode()).headers(framework.getHeaders())
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(body(new ApiException(code), request));
        }
        captureInternalError(exception, request);
        LOG.error("Unhandled request exception traceId={}", RequestTraceFilter.traceId(request), exception);
        return respond(new ApiException(ErrorCode.INTERNAL_ERROR), request);
    }

    /** R-14-18 (E11-T06): capture each handled 500 once, before MVC resolves it; Sentry's own resolver runs later and never sees it. */
    private void captureInternalError(Exception exception, HttpServletRequest request) {
        if (sentry != null) {
            var event = new io.sentry.SentryEvent(exception);
            event.setTag("traceId", RequestTraceFilter.traceId(request));
            sentry.captureEvent(event);
        }
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiError> forbidden(HttpServletRequest request) {
        return handle(new ApiException(ErrorCode.FORBIDDEN), request);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handle(ApiException exception, HttpServletRequest request) {
        if (exception.code() == ErrorCode.INTERNAL_ERROR) {
            // A 500 the code chose to answer (e.g. a provider outage) is still an internal error for telemetry.
            captureInternalError(exception, request);
            LOG.error("Internal error answered traceId={}", RequestTraceFilter.traceId(request), exception);
        }
        return respond(exception, request);
    }

    private ResponseEntity<ApiError> respond(ApiException exception, HttpServletRequest request) {
        LOG.debug("Request rejected code={} traceId={}", exception.code(), RequestTraceFilter.traceId(request));
        var response = ResponseEntity.status(exception.code().httpStatus());
        if (exception.details().get("retryAfter") instanceof Number retry) { response.header("Retry-After", retry.toString()); }
        return response.body(body(exception, request));
    }

    /** CONVENCIONS_API §7 (E62): the stored response of a repeated `Idempotency-Key`, once the route's authorization accepted the token. */
    @ExceptionHandler(IdempotentReplay.class)
    public void replay(IdempotentReplay replay, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        replay.writeTo(response);
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<ApiError> notImplemented(HttpServletRequest request) {
        return handle(new ApiException(ErrorCode.NOT_IMPLEMENTED), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        var errors = List.of(new FieldError(exception.getName(), "TYPE_MISMATCH", null));
        return rejected(new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("fieldErrors", errors)), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> uploadTooLarge(HttpServletRequest request) {
        return rejected(new ApiException(ErrorCode.FILE_TOO_LARGE), request);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiError> validation(BindException exception, HttpServletRequest request) {
        var errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), error.getCode(), error.getDefaultMessage())).toList();
        return rejected(new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("fieldErrors", errors)), request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> validation(ConstraintViolationException exception, HttpServletRequest request) {
        var errors = exception.getConstraintViolations().stream().map(violation -> new FieldError(
                violation.getPropertyPath().toString(),
                violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName(),
                violation.getMessage())).toList();
        return rejected(new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("fieldErrors", errors)), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> malformed(HttpServletRequest request) {
        return rejected(new ApiException(ErrorCode.VALIDATION_ERROR), request);
    }

    private ResponseEntity<ApiError> rejected(ApiException exception, HttpServletRequest request) {
        LOG.warn("Framework request rejected code={} traceId={}", exception.code(), RequestTraceFilter.traceId(request));
        return handle(exception, request);
    }

    public record FieldError(String field, String code, String message) { }
}
