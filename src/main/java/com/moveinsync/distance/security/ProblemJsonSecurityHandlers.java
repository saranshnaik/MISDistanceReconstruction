package com.moveinsync.distance.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.MDC;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * 401 and 403 are decided inside the security filter chain before any controller runs, so
 * the normal exception handler never sees them; these write the same problem-details body by
 * hand. Why a token was refused (expired, bad signature...) is deliberately not shown.
 */
public class ProblemJsonSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    /** Not logged in, or the token was refused. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer");
        write(response, 401, "Unauthorized", "UNAUTHENTICATED", "authentication required");
    }

    /** Logged in, but this role may not do that. */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        write(response, 403, "Forbidden", "FORBIDDEN", "you are not allowed to do this");
    }

    private static void write(HttpServletResponse response, int status, String title, String code,
                              String detail) throws IOException {
        String traceId = MDC.get("traceId");
        String body = "{\"status\":" + status + ",\"title\":\"" + title + "\",\"code\":\"" + code
                + "\",\"detail\":\"" + detail + "\""
                + (traceId == null ? "" : ",\"traceId\":\"" + traceId + "\"") + "}";
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }
}
