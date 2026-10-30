package com.moveinsync.distance.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Gives every request a trace id and writes one summary line when it ends.
 *
 * The id comes from the caller's X-Request-Id header if it looks sane, otherwise we make one.
 * It goes into the logging context (so EVERY log line of this request carries it), into the
 * response header, and into error bodies, so "what happened to request abc123?" is one search.
 *
 * Runs before the security filters so even rejected (401/403) requests have an id.
 *
 * The summary line logs the route PATTERN (/api/trips/{tripRef}), not the real path, so trip
 * refs and anything else in the URL never reach the logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "traceId";

    /** Letters, digits, dot, underscore, dash: nothing that could forge a log line or a header. */
    private static final Pattern ACCEPTABLE_ID = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    private static final Logger log = LoggerFactory.getLogger(RequestTraceFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = chooseTraceId(request.getHeader(HEADER));
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long millis = (System.nanoTime() - started) / 1_000_000;
            log.info("request method={} route={} status={} durationMs={}",
                    request.getMethod(), route(request), response.getStatus(), millis);
            MDC.remove(MDC_KEY);
        }
    }

    private static String chooseTraceId(String fromCaller) {
        if (fromCaller != null && ACCEPTABLE_ID.matcher(fromCaller).matches()) {
            return fromCaller;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String route(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern == null ? "unmatched" : pattern.toString();
    }
}
