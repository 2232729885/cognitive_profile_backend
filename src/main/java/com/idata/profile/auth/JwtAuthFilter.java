package com.idata.profile.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String ATTR_USER_ID = "userId";
    public static final String ATTR_USERNAME = "username";
    public static final String ATTR_ROLE = "role";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (isWhitelisted(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            unauthorized(response);
            return;
        }

        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty() || !jwtTokenProvider.validateToken(token)) {
            unauthorized(response);
            return;
        }

        try {
            request.setAttribute(ATTR_USER_ID, jwtTokenProvider.extractUserId(token));
            request.setAttribute(ATTR_USERNAME, jwtTokenProvider.extractUsername(token));
            request.setAttribute(ATTR_ROLE, jwtTokenProvider.extractRole(token));
        } catch (IllegalArgumentException e) {
            unauthorized(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isWhitelisted(HttpServletRequest request) {
        String method = request.getMethod();
        String path = requestPath(request);

        if (HttpMethod.POST.matches(method)
                && ("/api/auth/login".equals(path) || "/api/auth/register".equals(path))) {
            return true;
        }
        if (HttpMethod.GET.matches(method) && ("/actuator".equals(path) || path.startsWith("/actuator/"))) {
            return true;
        }
        if (HttpMethod.GET.matches(method) && path.matches("/api/analysis/[^/]+/stream")) {
            return true;
        }
        if (HttpMethod.POST.matches(method) && path.startsWith("/api/jobs/")) {
            return true;
        }
        if (HttpMethod.POST.matches(method) && path.startsWith("/llm/")) {
            return true;
        }
        if (HttpMethod.POST.matches(method) && (path.startsWith("/mock/") || path.startsWith("/debug/"))) {
            return true;
        }
        return false;
    }

    private String requestPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            return path.substring(contextPath.length());
        }
        return path;
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"code\":\"UNAUTHORIZED\",\"message\":\"\u672a\u8ba4\u8bc1\u6216token\u65e0\u6548\"}");
    }
}
