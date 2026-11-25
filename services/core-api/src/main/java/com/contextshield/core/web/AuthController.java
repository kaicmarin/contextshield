package com.contextshield.core.web;

import com.contextshield.core.config.CurrentUser;
import com.contextshield.core.config.JwtService;
import com.contextshield.core.config.LoginAttemptLimiter;
import com.contextshield.core.domain.*;
import com.contextshield.core.repo.*;
import com.contextshield.core.service.AuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final AppUserRepository users;
    private final CustomerRepository customers;
    private final DeviceRepository devices;
    private final AddressRepository addresses;
    private final CardRepository cards;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AuditService audit;
    private final LoginAttemptLimiter loginLimiter;

    public AuthController(AppUserRepository users, CustomerRepository customers, DeviceRepository devices, AddressRepository addresses,
                          CardRepository cards, PasswordEncoder encoder, JwtService jwt, AuditService audit,
                          LoginAttemptLimiter loginLimiter) {
        this.users = users;
        this.customers = customers;
        this.devices = devices;
        this.addresses = addresses;
        this.cards = cards;
        this.encoder = encoder;
        this.jwt = jwt;
        this.audit = audit;
        this.loginLimiter = loginLimiter;
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}

    public record UserView(Long id, String email, String name, String role, String customerId, boolean active) {
        static UserView of(AppUser u) {
            return new UserView(u.getId(), u.getEmail(), u.getName(), u.getRole().name(), u.getCustomerId(), u.isActive());
        }
    }

    public record TokenResponse(String token, Instant expiresAt, UserView user) {}

    @PostMapping("/auth/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req) {
        if (!loginLimiter.allow(req.email())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Muitas tentativas de login. Aguarde um minuto e tente novamente.");
        }
        AppUser u = users.findByEmailIgnoreCase(req.email().trim())
                .filter(x -> x.isActive() && encoder.matches(req.password(), x.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "E-mail ou senha incorretos"));
        var issued = jwt.issue(u);
        audit.log(u.getEmail(), "LOGIN", null, null);
        return new TokenResponse(issued.token(), issued.expiresAt(), UserView.of(u));
    }

    public record RegisterRequest(@NotBlank @Size(min = 3, max = 120) String name,
                                  @NotBlank @Email String email,
                                  @NotBlank @Size(min = 8, max = 72, message = "A senha precisa ter de 8 a 72 caracteres") String password,
                                  @NotBlank String city,
                                  @NotBlank String addressLine,
                                  String district,
                                  String zip) {}

    /** Cadastro de cliente novo: cria cliente, casa, aparelho atual e cartão simulado. Histórico começa vazio. */
    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public TokenResponse register(@Valid @RequestBody RegisterRequest req) {
        if (users.existsByEmailIgnoreCase(req.email())) throw ApiException.conflict("Já existe uma conta com este e-mail");
        String id = "CUST-" + String.format("%03d", customers.count() + 1);
        while (customers.existsById(id)) id = "CUST-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String first = req.name().trim().split("\\s+")[0];
        String[] parts = req.name().trim().split("\\s+");
        String initials = (parts[0].substring(0, 1) + (parts.length > 1 ? parts[parts.length - 1].substring(0, 1) : "")).toUpperCase();

        Customer c = new Customer();
        c.setId(id);
        c.setName(req.name().trim());
        c.setFirstName(first);
        c.setInitials(initials);
        c.setEmail(req.email().trim().toLowerCase());
        c.setCity(req.city().trim());
        c.setSince(LocalDate.now());
        c.setSegment("Essencial");
        c.setAvgTicket90d(new BigDecimal("300.00"));
        c.setPurchases90d(0);
        customers.save(c);

        Address home = new Address();
        home.setId("ADDR-" + id + "-HOME");
        home.setLabel("Casa");
        home.setLine1(req.addressLine().trim());
        home.setDistrict(req.district());
        home.setCity(req.city().trim());
        home.setZip(req.zip());
        home.setKind("home");
        home.getCustomerIds().add(id);
        addresses.save(home);

        Device d = new Device();
        d.setId("DEV-" + id + "-WEB");
        d.setCustomerId(id);
        d.setName("Navegador do cadastro");
        d.setDetail("Web");
        d.setKind("desktop");
        d.setLocation(req.city().trim());
        d.setFirstSeen(LocalDateTime.now().withNano(0));
        d.setTrusted(true);
        devices.save(d);

        Card card = new Card();
        card.setId("CARD-SIM-" + id.replace("CUST-", ""));
        card.setCustomerId(id);
        card.setLast4(String.format("%04d", new Random().nextInt(10000)));
        card.setHolder(req.name().toUpperCase());
        card.setProduct("Aureon Essencial");
        card.setKind("Crédito");
        card.setExpiry("12/31");
        card.setCreditLimit(new BigDecimal("3000.00"));
        card.setAvailable(new BigDecimal("3000.00"));
        card.setStatus("Ativo");
        cards.save(card);

        AppUser u = new AppUser();
        u.setEmail(c.getEmail());
        u.setName(c.getName());
        u.setRole(AppUser.Role.CUSTOMER);
        u.setCustomerId(id);
        u.setPasswordHash(encoder.encode(req.password()));
        u.setActive(true);
        u.setCreatedAt(LocalDateTime.now());
        users.save(u);
        audit.log(u.getEmail(), "REGISTER", id, null);
        var issued = jwt.issue(u);
        return new TokenResponse(issued.token(), issued.expiresAt(), UserView.of(u));
    }

    /** Perfil do usuário logado. Para clientes, vem junto com cartões, aparelhos e endereços. */
    @GetMapping("/me")
    public Map<String, Object> me() {
        CurrentUser cu = Auth.user();
        AppUser u = users.findByEmailIgnoreCase(cu.email()).orElseThrow(() -> ApiException.notFound("Usuário"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", UserView.of(u));
        if (u.getCustomerId() != null) {
            customers.findById(u.getCustomerId()).ifPresent(c -> out.put("customer", c));
            out.put("cards", cards.findByCustomerId(u.getCustomerId()));
            out.put("devices", devices.findByCustomerIdOrderByTrustedDescFirstSeenAsc(u.getCustomerId()));
            out.put("addresses", addresses.findOwnedBy(u.getCustomerId()));
        }
        return out;
    }

    public record NewAddress(@NotBlank String label, @NotBlank String line1, String line2, String district,
                             @NotBlank String city, String zip) {}

    @PostMapping("/me/addresses")
    @ResponseStatus(HttpStatus.CREATED)
    public Address addAddress(@Valid @RequestBody NewAddress req) {
        CurrentUser cu = Auth.customer();
        Address a = new Address();
        a.setId("ADDR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        a.setLabel(req.label());
        a.setLine1(req.line1());
        a.setLine2(req.line2());
        a.setDistrict(req.district());
        a.setCity(req.city());
        a.setZip(req.zip());
        a.setKind("other");
        a.getCustomerIds().add(cu.customerId());
        audit.log(cu.email(), "ADDRESS_ADDED", a.getId(), null);
        return addresses.save(a);
    }
}
