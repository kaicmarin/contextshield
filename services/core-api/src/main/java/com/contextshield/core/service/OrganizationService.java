package com.contextshield.core.service;

import com.contextshield.core.config.CurrentUser;
import com.contextshield.core.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** A organização vem do usuário autenticado. Um id enviado no corpo da requisição não escolhe a empresa. */
@Service
public class OrganizationService {

    private final JdbcTemplate jdbc;

    public OrganizationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long idOf(CurrentUser user) {
        return idOfEmail(user.email());
    }

    public long idOfEmail(String email) {
        if (email == null || email.isBlank()) return 1L;
        Long id = jdbc.query("select organization_id from app_user where lower(email) = lower(?)",
                rs -> rs.next() ? (Long) rs.getObject(1) : null, email);
        return id == null ? 1L : id;
    }

    public Map<String, Object> current(CurrentUser user) {
        long id = idOf(user);
        return jdbc.query("""
                select id, code, name, profile from organization where id = ?
                """, rs -> {
            if (!rs.next()) throw ApiException.notFound("Organização");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("code", rs.getString("code"));
            row.put("name", rs.getString("name"));
            row.put("profile", rs.getString("profile"));
            return row;
        }, id);
    }

    public void requireSame(CurrentUser user, Long resourceOrganization) {
        requireSame(user, resourceOrganization, "Transação");
    }

    public void requireSame(CurrentUser user, Long resourceOrganization, String resource) {
        long mine = idOf(user);
        long theirs = resourceOrganization == null ? 1L : resourceOrganization;
        if (mine != theirs) throw ApiException.notFound(resource);
    }
}
