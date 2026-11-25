package com.contextshield.core.config;

import com.contextshield.core.domain.AppUser;
import com.contextshield.core.repo.AppUserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** Lê "Authorization: Bearer <jwt>", valida a assinatura e coloca o usuário no contexto do Spring. */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwt;
    private final AppUserRepository users;

    public JwtAuthFilter(JwtService jwt, AppUserRepository users) {
        this.jwt = jwt;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims c = jwt.parse(header.substring(7));
                String role = c.get("role", String.class);
                if ("SERVICE".equals(role)) {
                    CurrentUser user = new CurrentUser(c.getSubject(), c.getSubject(), role, null);
                    var auth = new UsernamePasswordAuthenticationToken(user, null, List.of(new SimpleGrantedAuthority("ROLE_SERVICE")));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                } else {
                    AppUser db = users.findByEmailIgnoreCase(c.getSubject()).orElse(null);
                    if (db == null || !db.isActive()) {
                        SecurityContextHolder.clearContext();
                    } else if (!db.getRole().name().equals(role)) {
                        SecurityContextHolder.clearContext();
                    } else {
                        long tv = 0L;
                        Object tvClaim = c.get("tv");
                        if (tvClaim instanceof Number n) tv = n.longValue();
                        if (tv != db.getTokenVersion()) {
                            SecurityContextHolder.clearContext();
                        } else {
                            CurrentUser user = new CurrentUser(db.getEmail(), db.getName(), db.getRole().name(), db.getCustomerId());
                            var auth = new UsernamePasswordAuthenticationToken(user, null,
                                    List.of(new SimpleGrantedAuthority("ROLE_" + db.getRole().name())));
                            SecurityContextHolder.getContext().setAuthentication(auth);
                        }
                    }
                }
            } catch (JwtException | IllegalArgumentException e) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(req, res);
    }
}
