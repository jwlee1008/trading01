package com.signallab.api.global.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

@Component
public class PublicRouteRegistry {

    private static final Set<RoutePattern> PUBLIC_ROUTES = Set.of(
        new RoutePattern(HttpMethod.GET, "/v1/health"),
        new RoutePattern(HttpMethod.GET, "/v1/provider/status"),
        new RoutePattern(HttpMethod.GET, "/v1/catalog"),
        new RoutePattern(HttpMethod.GET, "/v1/universe-versions"),
        new RoutePattern(HttpMethod.GET, "/v1/market/kospi/top10"),
        new RoutePattern(HttpMethod.GET, "/v1/market/instruments/*/daily-prices"),
        new RoutePattern(HttpMethod.GET, "/v1/rankings"),
        new RoutePattern(HttpMethod.GET, "/v1/profiles/*/public"),
        // Internal worker routes authenticate with a service token in their controller,
        // rather than an end-user bearer token.
        new RoutePattern(HttpMethod.POST, "/v1/internal/worker/cycle"),
        new RoutePattern(HttpMethod.GET, "/v1/internal/worker/state"),
        new RoutePattern(HttpMethod.POST, "/v1/internal/worker/tasks/*"),
        new RoutePattern(HttpMethod.POST, "/v1/internal/worker/runs/*/retry")
    );

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public boolean isPublic(HttpServletRequest request) {
        String servletPath = request.getRequestURI();
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        return PUBLIC_ROUTES.stream().anyMatch(route -> route.matches(method, servletPath, pathMatcher));
    }

    private record RoutePattern(HttpMethod method, String pattern) {
        boolean matches(HttpMethod requestMethod, String path, AntPathMatcher matcher) {
            return method == requestMethod && matcher.match(pattern, path);
        }
    }
}
