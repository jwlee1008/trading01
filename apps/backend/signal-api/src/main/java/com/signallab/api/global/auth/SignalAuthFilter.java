package com.signallab.api.global.auth;

import com.signallab.api.global.config.SignalProperties;
import com.signallab.api.domain.account.service.AccountService;
import com.signallab.api.global.web.PublicRouteRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class SignalAuthFilter extends OncePerRequestFilter {

    private final SignalProperties properties;
    private final AccountService accountService;
    private final PublicRouteRegistry publicRoutes;
    private JwtDecoder jwtDecoder;

    public SignalAuthFilter(
        SignalProperties properties,
        AccountService accountService,
        PublicRouteRegistry publicRoutes
    ) {
        this.properties = properties;
        this.accountService = accountService;
        this.publicRoutes = publicRoutes;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        if (publicRoutes.isPublic(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            AuthenticatedUser user = authenticateSupabase(request);

            if (accountService.isAccountDeleted(user.userId())) {
                writeError(response, HttpStatus.GONE, "삭제된 계정입니다.");
                return;
            }

            request.setAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE, user);
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.userId(), null, java.util.List.of())
            );
            filterChain.doFilter(request, response);
        } catch (AuthRejectedException failure) {
            writeError(response, failure.getStatus(), failure.getMessage());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private AuthenticatedUser authenticateSupabase(HttpServletRequest request) throws AuthRejectedException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw AuthRejectedException.unauthorized("인증 토큰이 필요합니다.");
        }

        String token = authorization.substring("Bearer ".length()).trim();
        String supabaseUrl = properties.normalizedSupabaseUrl();
        if (supabaseUrl.isBlank()) {
            throw AuthRejectedException.unavailable("SUPABASE_URL 설정이 필요합니다.");
        }

        try {
            Jwt jwt = jwtDecoder().decode(token);
            String issuer = supabaseUrl + "/auth/v1";
            if (!issuer.equals(jwt.getIssuer().toString())) {
                throw AuthRejectedException.unauthorized("인증 토큰 검증에 실패했습니다.");
            }
            Object audience = jwt.getAudience();
            if (audience instanceof java.util.Collection<?> values && !values.contains("authenticated")) {
                throw AuthRejectedException.unauthorized("인증 토큰 검증에 실패했습니다.");
            }
            String subject = jwt.getSubject();
            if (subject == null || subject.isBlank()) {
                throw AuthRejectedException.unauthorized("사용자 식별자가 없습니다.");
            }
            return new AuthenticatedUser(subject);
        } catch (JwtException error) {
            throw AuthRejectedException.unauthorized("인증 토큰 검증에 실패했습니다.");
        }
    }

    private JwtDecoder jwtDecoder() {
        if (jwtDecoder == null) {
            String jwkSetUri = properties.normalizedSupabaseUrl() + "/auth/v1/.well-known/jwks.json";
            // Current local Supabase projects issue asymmetric ES256 access tokens.
            // Nimbus defaults to RS256 unless the accepted algorithm is declared.
            jwtDecoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.ES256)
                .build();
        }
        return jwtDecoder;
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write("{\"message\":\"" + escapeJson(message) + "\",\"statusCode\":" + status.value() + "}");
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class AuthRejectedException extends RuntimeException {
        private final HttpStatus status;

        private AuthRejectedException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }

        HttpStatus getStatus() {
            return status;
        }

        static AuthRejectedException unauthorized(String message) {
            return new AuthRejectedException(HttpStatus.UNAUTHORIZED, message);
        }

        static AuthRejectedException unavailable(String message) {
            return new AuthRejectedException(HttpStatus.SERVICE_UNAVAILABLE, message);
        }
    }
}
