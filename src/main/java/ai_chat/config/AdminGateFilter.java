package ai_chat.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Protects {@code /admin/**}: optional disable via config; optional shared secret header.
 */
@Component
@Order(0)
public class AdminGateFilter extends OncePerRequestFilter {

    private final AdminProperties adminProperties;

    public AdminGateFilter(AdminProperties adminProperties) {
        this.adminProperties = adminProperties;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String p = request.getServletPath();
        return p == null || !p.startsWith("/admin");
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        if (!adminProperties.isEnabled()) {
            response.sendError(HttpStatus.NOT_FOUND.value(), "Admin API is disabled");
            return;
        }
        if (StringUtils.hasText(adminProperties.getApiKey())) {
            String header = request.getHeader("X-Admin-Key");
            if (!adminProperties.getApiKey().equals(header)) {
                response.sendError(HttpStatus.UNAUTHORIZED.value(), "Invalid or missing X-Admin-Key");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
