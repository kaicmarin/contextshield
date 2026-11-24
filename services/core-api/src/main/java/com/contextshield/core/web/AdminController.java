package com.contextshield.core.web;

import com.contextshield.core.config.CurrentUser;
import com.contextshield.core.domain.AppUser;
import com.contextshield.core.repo.AppUserRepository;
import com.contextshield.core.repo.CustomerRepository;
import com.contextshield.core.service.AuditService;
import com.contextshield.core.service.OrganizationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/** CRUD de usuários (papel ADMIN). */
@RestController
@RequestMapping("/api/admin/users")
public class AdminController {

    private final AppUserRepository users;
    private final CustomerRepository customers;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final OrganizationService organizations;

    public AdminController(AppUserRepository users, CustomerRepository customers, PasswordEncoder encoder, AuditService audit,
                           OrganizationService organizations) {
        this.users = users;
        this.customers = customers;
        this.encoder = encoder;
        this.audit = audit;
        this.organizations = organizations;
    }

    @GetMapping
    public List<AuthController.UserView> list() {
        long organizationId = organizations.idOf(Auth.user());
        return users.findAll(org.springframework.data.domain.Sort.by("id")).stream()
                .filter(user -> organizationId == (user.getOrganizationId() == null ? 1L : user.getOrganizationId()))
                .map(AuthController.UserView::of).toList();
    }

    @GetMapping("/{id}")
    public AuthController.UserView get(@PathVariable Long id) {
        AppUser user = users.findById(id).orElseThrow(() -> ApiException.notFound("Usuário"));
        organizations.requireSame(Auth.user(), user.getOrganizationId(), "Usuário");
        return AuthController.UserView.of(user);
    }

    public record CreateUser(@NotBlank @Email String email, @NotBlank @Size(max = 120) String name,
                             @NotBlank @Size(min = 8, max = 72) String password,
                             @NotBlank @Pattern(regexp = "CUSTOMER|ANALYST|ADMIN") String role, String customerId) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AuthController.UserView create(@Valid @RequestBody CreateUser req) {
        if (users.existsByEmailIgnoreCase(req.email())) throw ApiException.conflict("Já existe um usuário com este e-mail");
        if ("CUSTOMER".equals(req.role()) && (req.customerId() == null || !customers.existsById(req.customerId())))
            throw ApiException.badRequest("Usuário cliente precisa de um customerId existente");
        AppUser u = new AppUser();
        u.setEmail(req.email().trim().toLowerCase());
        u.setName(req.name().trim());
        u.setRole(AppUser.Role.valueOf(req.role()));
        u.setCustomerId("CUSTOMER".equals(req.role()) ? req.customerId() : null);
        u.setPasswordHash(encoder.encode(req.password()));
        u.setActive(true);
        u.setCreatedAt(LocalDateTime.now());
        u.setOrganizationId(organizations.idOf(Auth.user()));
        users.save(u);
        audit.log(Auth.user().email(), "USER_CREATED", u.getEmail(), u.getRole().name());
        return AuthController.UserView.of(u);
    }

    public record UpdateUser(@Size(max = 120) String name, @Pattern(regexp = "CUSTOMER|ANALYST|ADMIN") String role,
                             Boolean active, @Size(min = 8, max = 72) String password) {}

    @PutMapping("/{id}")
    public AuthController.UserView update(@PathVariable Long id, @Valid @RequestBody UpdateUser req) {
        AppUser u = users.findById(id).orElseThrow(() -> ApiException.notFound("Usuário"));
        CurrentUser me = Auth.user();
        organizations.requireSame(me, u.getOrganizationId(), "Usuário");
        if (u.getEmail().equalsIgnoreCase(me.email()) && (Boolean.FALSE.equals(req.active()) || (req.role() != null && !"ADMIN".equals(req.role()))))
            throw ApiException.conflict("Você não pode remover o seu próprio acesso de administrador");
        if (req.name() != null && !req.name().isBlank()) u.setName(req.name().trim());
        if (req.role() != null) {
            if ("CUSTOMER".equals(req.role()) && u.getCustomerId() == null) throw ApiException.badRequest("Usuário sem cliente vinculado");
            u.setRole(AppUser.Role.valueOf(req.role()));
        }
        if (req.active() != null) u.setActive(req.active());
        if (req.password() != null) u.setPasswordHash(encoder.encode(req.password()));
        users.save(u);
        audit.log(me.email(), "USER_UPDATED", u.getEmail(), null);
        return AuthController.UserView.of(u);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        AppUser u = users.findById(id).orElseThrow(() -> ApiException.notFound("Usuário"));
        organizations.requireSame(Auth.user(), u.getOrganizationId(), "Usuário");
        if (u.getEmail().equalsIgnoreCase(Auth.user().email())) throw ApiException.conflict("Você não pode excluir a sua própria conta");
        users.delete(u);
        audit.log(Auth.user().email(), "USER_DELETED", u.getEmail(), null);
    }
}
