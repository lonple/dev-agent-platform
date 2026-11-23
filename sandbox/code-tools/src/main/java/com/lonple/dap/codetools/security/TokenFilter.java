package com.lonple.dap.codetools.security;

import com.lonple.dap.codetools.config.SandboxProps;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class TokenFilter extends OncePerRequestFilter {

    private final SandboxProps props;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/health") || path.equals("/actuator/health");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = request.getHeader("X-Sandbox-Token");
        if (token == null || !token.equals(props.getSharedToken())) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"SB_TOKEN_INVALID\",\"message\":\"invalid token\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
