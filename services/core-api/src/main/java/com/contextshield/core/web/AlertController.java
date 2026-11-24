package com.contextshield.core.web;

import com.contextshield.core.config.CurrentUser;
import com.contextshield.core.domain.Alert;
import com.contextshield.core.repo.AlertRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Avisos do app do banco: listar, marcar como lido e apagar. */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertRepository repo;

    public AlertController(AlertRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<Alert> mine() {
        return repo.findByCustomerIdOrderByCreatedAtDesc(Auth.customer().customerId());
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unread() {
        return Map.of("unread", repo.countByCustomerIdAndReadAtIsNull(Auth.customer().customerId()));
    }

    private Alert own(Long id) {
        CurrentUser u = Auth.customer();
        Alert a = repo.findById(id).orElseThrow(() -> ApiException.notFound("Aviso"));
        if (!a.getCustomerId().equals(u.customerId())) throw ApiException.notFound("Aviso");
        return a;
    }

    @PatchMapping("/{id}/read")
    public Alert read(@PathVariable Long id) {
        Alert a = own(id);
        if (a.getReadAt() == null) a.setReadAt(LocalDateTime.now().withNano(0));
        return repo.save(a);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        repo.delete(own(id));
    }
}
