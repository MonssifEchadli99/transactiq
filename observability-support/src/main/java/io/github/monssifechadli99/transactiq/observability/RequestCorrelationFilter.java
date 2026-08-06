package io.github.monssifechadli99.transactiq.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/** Adds a safe correlation value without inspecting or retaining request bodies. */
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestCorrelationFilter extends OncePerRequestFilter {

    static final String HEADER_NAME = "X-Request-Id";
    static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String requestId = safeRequestId(request.getHeader(HEADER_NAME));
        String previousRequestId = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER_NAME, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (previousRequestId == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previousRequestId);
            }
        }
    }

    private static String safeRequestId(String candidate) {
        if (candidate != null) {
            try {
                String canonical = UUID.fromString(candidate).toString();
                if (canonical.equalsIgnoreCase(candidate)) {
                    return canonical;
                }
            } catch (IllegalArgumentException ignored) {
                // Treat untrusted header content as invalid without retaining it.
            }
        }
        return UUID.randomUUID().toString();
    }
}
