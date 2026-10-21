package com.delivery.harness.gateway.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** The bundled workbench uses same-origin resources and never embeds third-party scripts. */
@Component
@Order(0)
public class BrowserBoundaryFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        response.setHeader("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
        response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("Referrer-Policy","same-origin");
        if(request.getRequestURI().startsWith("/api/"))response.setHeader("Cache-Control","no-store");
        chain.doFilter(request,response);
    }
}
