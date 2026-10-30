package com.planb.emotionfriend.api.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {
    private static final String ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";

    public static String traceId(HttpServletRequest request) {
        Object existing = request.getAttribute(ATTRIBUTE);
        if (existing instanceof String id) return id;
        String id = UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, id);
        return id;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String previous = MDC.get("traceId");
        String id = traceId(request);
        response.setHeader("X-Trace-Id", id);
        MDC.put("traceId", id);
        try {
            chain.doFilter(request, response);
        } finally {
            if (previous == null) MDC.remove("traceId"); else MDC.put("traceId", previous);
        }
    }
}
