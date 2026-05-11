package com.minidb.order.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minidb.order.dto.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);
    private static final String HEADER_API_KEY = "X-Api-Key";
    private static final String HEADER_USER_ID = "X-User-Id";

    private final String configuredApiKey;
    private final ObjectMapper objectMapper;

    public ApiKeyFilter(@Value("${minidb.api.key:}") String apiKey,
                        ObjectMapper objectMapper) {
        this.configuredApiKey = apiKey;
        this.objectMapper = objectMapper;

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("MINIDB_API_KEY is not set — API authentication is DISABLED. "
                    + "All /api/* endpoints are publicly accessible.");
        } else {
            log.info("API Key authentication is ENABLED (key length={})", apiKey.length());
        }
    }


    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();

        // Whitelist: runtime mode check (needed for health/startup)
        if (path.equals("/api/runtime/mode")) {
            chain.doFilter(request, response);
            return;
        }

        // Whitelist: Swagger / OpenAPI docs (non-sensitive)
        if (path.startsWith("/api-docs") || path.startsWith("/swagger-ui")) {
            chain.doFilter(request, response);
            return;
        }

        // Whitelist: Actuator health (no auth needed for liveness)
        if (path.equals("/actuator/health") || path.equals("/actuator/health/liveness")) {
            chain.doFilter(request, response);
            return;
        }

        // Only filter API paths
        if (!path.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }

        // If no API key is configured, allow all (test/dev mode)
        if (configuredApiKey == null || configuredApiKey.isBlank()) {
            log.debug("API Key not configured — allowing request to {}", path);
            chain.doFilter(request, response);
            return;
        }

        String clientKey = request.getHeader(HEADER_API_KEY);

        if (clientKey == null || clientKey.isBlank()) {
            log.warn("Missing X-Api-Key header for {}", path);
            writeUnauthorized(response, "UNAUTHORIZED", "Missing X-Api-Key header. Please configure your API Key in Settings.");
            return;
        }

        if (!configuredApiKey.equals(clientKey)) {
            log.warn("Invalid X-Api-Key for {} (key length={})", path, clientKey.length());
            writeUnauthorized(response, "UNAUTHORIZED", "Invalid API Key. Please check your API Key in Settings.");
            return;
        }

        // API Key validated — set attribute so downstream can trust X-User-Id
        request.setAttribute("auth.apiKeyValidated", true);

        // Validate X-User-Id format if present
        String userIdHeader = request.getHeader(HEADER_USER_ID);
        if (userIdHeader != null) {
            try {
                long userId = Long.parseLong(userIdHeader.trim());
                if (userId < 1 || userId > 999_999_999L) {
                    writeUnauthorized(response, "INVALID_USER_ID",
                            "X-User-Id must be a positive integer between 1 and 999,999,999");
                    return;
                }
                request.setAttribute("auth.userId", userId);
            } catch (NumberFormatException e) {
                writeUnauthorized(response, "INVALID_USER_ID",
                        "X-User-Id must be a valid integer");
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response, String errorCode, String message)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ApiResponse<Void> body = ApiResponse.fail(errorCode, message);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
