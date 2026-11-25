package com.contextshield.core.service;

import com.contextshield.core.config.CurrentUser;
import com.contextshield.core.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** A organização vem do usuário autenticado. Um id enviado no corpo da requisição não escolhe a empresa. */
@Service
public class OrganizationService {

  private static final Set<String> SYSTEM_ACTORS = Set.of(
      "sistema", "Sistema", "Motor de risco", "core-api", "risk-engine");

  private final JdbcTemplate jdbc;

  public OrganizationService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public long idOf(CurrentUser user) {
    return idOfEmail(user.email());
  }

  /** Resolve a organização a partir do e-mail já autenticado. Sem usuário válido, não há acesso. */
  public long idOfEmail(String email) {
    if (email == null || email.isBlank()) {
      throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Sessão sem identidade de usuário");
    }
    email = email.trim();
    if (SYSTEM_ACTORS.contains(email)) {
      throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,
          "Ator de sistema não representa uma organização");
    }
    Long id = jdbc.query("select organization_id from app_user where lower(email) = lower(?)",
        rs -> rs.next() ? (Long) rs.getObject(1) : null, email);
    if (id == null) {
      throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Usuário não cadastrado");
    }
    return id;
  }

  /**
   * Tenta resolver a organização pelo e-mail, sem devolver org 1 por padrão.
   * Usado em auditoria e relatos quando o ator pode ser de sistema.
   */
  public Long tryIdOfEmail(String email) {
    if (email == null || email.isBlank() || SYSTEM_ACTORS.contains(email.trim())) return null;
    try {
      return idOfEmail(email);
    } catch (ApiException e) {
      return null;
    }
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
    if (resourceOrganization == null) {
      throw ApiException.notFound(resource);
    }
    if (mine != resourceOrganization) {
      throw ApiException.notFound(resource);
    }
  }

  /**
   * Organização para operações em nome de um cliente (checkout, seed).
   * Usa o e-mail autenticado quando válido; caso contrário, o vínculo do cliente no cadastro.
   */
  public long organizationForCustomer(String customerId, String actor) {
    Long fromActor = tryIdOfEmail(actor);
    if (fromActor != null) return fromActor;
    if (customerId == null || customerId.isBlank()) {
      throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
          "Não foi possível determinar a organização");
    }
    Long organizationId = jdbc.query("select organization_id from app_user where customer_id = ? limit 1",
        rs -> rs.next() ? (Long) rs.getObject(1) : null, customerId);
    if (organizationId == null) {
      throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
          "Não foi possível determinar a organização deste cliente");
    }
    return organizationId;
  }

  /** Org da compra, usado em chamadas internas quando só o id da transação é conhecido. */
  public long organizationOfTransaction(String txId) {
    if (txId == null || txId.isBlank()) throw ApiException.badRequest("Compra inválida");
    Long id = jdbc.query("select organization_id from transactions where id = ?",
        rs -> rs.next() ? (Long) rs.getObject(1) : null, txId);
    if (id == null) throw ApiException.notFound("Compra");
    return id;
  }
}