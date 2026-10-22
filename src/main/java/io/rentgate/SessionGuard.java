package io.rentgate;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

final class SessionGuard extends OncePerRequestFilter {
    private final AccountService accounts;
    SessionGuard(AccountService accounts) { this.accounts=accounts; }

    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if ((path.startsWith("/admin/") || path.equals("/") || path.equals("/index.html"))
                && authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken)) {
            try { accounts.actor(); }
            catch (ResponseStatusException invalid) {
                var session=request.getSession(false);
                if (session != null) session.invalidate();
                SecurityContextHolder.clearContext();
                if (path.startsWith("/admin/")) response.setStatus(401);
                else response.sendRedirect("/login");
                return;
            }
        }
        chain.doFilter(request,response);
    }
}
