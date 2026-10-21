package com.crm.tagtog;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Map;

@Component @Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TagtogAccessFilter extends OncePerRequestFilter {
    private final TagtogProperties config;
    private final TagtogService service;
    private final ObjectMapper json;
    public TagtogAccessFilter(TagtogProperties config, TagtogService service, ObjectMapper json) {
        this.config = config; this.service = service; this.json = json;
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String normalized = path.replace('\\', '/').replaceAll("/+", "/");
        String servletPath = request.getServletPath();
        if (!config.enabled || !(normalized.startsWith("/api") || servletPath != null && servletPath.startsWith("/api"))) {
            chain.doFilter(request, response); return;
        }
        try {
            if (path.contains("%") || path.contains(";") || path.contains("\\") || path.contains("//")
                    || path.indexOf(0) >= 0 || path.matches(".*/\\.\\.?(/.*)?"))
                throw new TagtogException(400, "INVALID_PATH", "Invalid API path");
            if ("OPTIONS".equals(request.getMethod())) { chain.doFilter(request, response); return; }
            if (path.equals("/api/auth/login") || path.equals("/api/auth/register")
                    || path.matches("/api/users/[^/]+/password(/.*)?") || path.startsWith("/api/dev"))
                throw new TagtogException(403, "CENTRAL_LOGIN_REQUIRED", "Use TAGTOG to manage your login");
            if (!exempt(request.getMethod(), path))
                service.authorize(TagtogService.bearer(request.getHeader("Authorization")), request.getMethod(), path);
            response.setHeader("Cache-Control", "no-store");
        } catch (TagtogException ex) {
            response.setStatus(ex.status); response.setContentType("application/json"); response.setHeader("Cache-Control", "no-store");
            json.writeValue(response.getOutputStream(), Map.of("error", ex.code, "message", ex.getMessage())); return;
        }
        chain.doFilter(request, response);
    }
    private boolean exempt(String method, String path) {
        if ("POST".equals(method) && (path.equals("/api/tagtog/exchange") || path.equals("/api/auth/logout"))) return true;
        // Mapping endpoints have their own live IAM SUPER_ADMIN checks.
        if (path.startsWith("/api/tagtog/admin/")) return true;
        // Existing external email pixels remain public; no business data is returned.
        return "GET".equals(method) && path.matches("/api/event-participants/emails/track/[1-9][0-9]*");
    }
}
