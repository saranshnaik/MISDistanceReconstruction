package com.moveinsync.distance.web;

import com.moveinsync.distance.security.InvalidCredentialsException;
import com.moveinsync.distance.service.TripNotBillableException;
import com.moveinsync.distance.service.TripNotFoundException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every failure leaves the API in the same RFC 9457 problem-details shape, e.g.
 * { "status": 404, "title": "Not Found", "code": "TRIP_NOT_FOUND", "detail": "trip not
 * found", "traceId": "..." }
 * "code" is stable and machine-readable, "detail" a fixed sentence never built from input.
 * Nothing the caller sent is echoed back (no ids, coordinates, emails, parser snippets);
 * validation errors name the FIELD and the rule, never the rejected value. Unexpected errors
 * say "unexpected error" plus the traceId, and the real cause stays in the server log where
 * the traceId finds it.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(TripNotFoundException.class)
    public ProblemDetail tripNotFound() {
        return problem(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND", "trip not found");
    }

    @ExceptionHandler(TripNotBillableException.class)
    public ProblemDetail tripNotBillable() {
        return problem(HttpStatus.CONFLICT, "TRIP_NOT_BILLABLE", "trip cannot be billed yet");
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail invalidCredentials() {
        return problem(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "invalid credentials");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail conflict(DataIntegrityViolationException e) {
        log.warn("data conflict: {}", e.getClass().getSimpleName());
        return problem(HttpStatus.CONFLICT, "CONFLICT", "the request conflicts with the current state");
    }

    /** Last resort for anything we didn't plan for. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception e) {
        // class and first frame only, exception messages can contain request data
        StackTraceElement top = e.getStackTrace().length > 0 ? e.getStackTrace()[0] : null;
        log.error("unexpected error {} at {}", e.getClass().getName(), top);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "unexpected error");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            errors.add(Map.of("field", error.getField(), "rule", String.valueOf(error.getDefaultMessage())));
        }
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "request validation failed");
        body.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(body);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // the parser's message can quote the body, so it never goes out
        return ResponseEntity.badRequest()
                .body(problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "request body could not be read"));
    }

    /** Every other MVC error (405, 415, missing parameter...) gets the same shape. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        Object effectiveBody = body;
        if (effectiveBody == null && e instanceof ErrorResponse errorResponse) {
            // Spring builds this body a step later, but we need it now to add our fields
            effectiveBody = errorResponse.updateAndGetBody(null, LocaleContextHolder.getLocale());
        }
        if (effectiveBody instanceof ProblemDetail detail) {
            HttpStatus status = HttpStatus.valueOf(statusCode.value());
            detail.setProperty("code", status.name());
            detail.setDetail(status.getReasonPhrase().toLowerCase());
            detail.setInstance(neutralInstance(status.name()));
            addTraceId(detail);
        }
        return super.handleExceptionInternal(e, effectiveBody, headers, statusCode, request);
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        problem.setInstance(neutralInstance(code));
        addTraceId(problem);
        return problem;
    }

    /**
     * Spring would fill "instance" with the request path, which has trip refs in it. A fixed
     * value stops that; the path only ever appears in the access log.
     */
    private static URI neutralInstance(String code) {
        return URI.create("urn:error:" + code.toLowerCase());
    }

    private static void addTraceId(ProblemDetail problem) {
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            problem.setProperty("traceId", traceId);
        }
    }
}
