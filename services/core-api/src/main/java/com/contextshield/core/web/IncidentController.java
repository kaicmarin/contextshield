package com.contextshield.core.web;

import com.contextshield.core.config.CurrentUser;
import com.contextshield.core.domain.Incident;
import com.contextshield.core.service.IncidentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/** "Fui vítima": CRUD dos relatos do próprio cliente. */
@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentService service;

    public IncidentController(IncidentService service) {
        this.service = service;
    }

    public record IncidentRequest(String txId,
                                  @NotBlank String type,
                                  @NotBlank String channel,
                                  @NotBlank @Size(min = 15, max = 4000, message = "Conte com um pouco mais de detalhe (mínimo 15 caracteres)") String description,
                                  @Size(max = 60) String contactHandle,
                                  @Size(max = 200) String contactLink,
                                  @Size(max = 200) String destinationText,
                                  LocalDateTime occurredAt,
                                  @AssertTrue(message = "É preciso autorizar o uso do relato") boolean consent) {
        IncidentService.Draft draft(String customerId) {
            return new IncidentService.Draft(customerId, txId, type, channel, description, contactHandle, contactLink,
                    destinationText, occurredAt, consent);
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Incident create(@Valid @RequestBody IncidentRequest req) {
        CurrentUser u = Auth.customer();
        return service.create(req.draft(u.customerId()), u.email(), null, null);
    }

    @GetMapping
    public List<Incident> mine() {
        return service.mine(Auth.customer());
    }

    @GetMapping("/{id}")
    public Incident get(@PathVariable String id) {
        return service.get(id, Auth.user());
    }

    @PutMapping("/{id}")
    public Incident update(@PathVariable String id, @Valid @RequestBody IncidentRequest req) {
        CurrentUser u = Auth.customer();
        return service.update(id, req.draft(u.customerId()), u);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        service.delete(id, Auth.user());
    }
}
