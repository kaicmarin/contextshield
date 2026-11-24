package com.contextshield.core.web;

import com.contextshield.core.config.CurrentUser;
import com.contextshield.core.domain.Address;
import com.contextshield.core.domain.Transaction;
import com.contextshield.core.repo.AddressRepository;
import com.contextshield.core.repo.DeviceRepository;
import com.contextshield.core.repo.TransactionRepository;
import com.contextshield.core.service.TransactionService;
import com.contextshield.core.service.Views;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Checkout da loja e compras do cliente logado. */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService service;
    private final TransactionRepository txs;
    private final AddressRepository addresses;
    private final DeviceRepository devices;
    private final Views views;

    public TransactionController(TransactionService service, TransactionRepository txs, AddressRepository addresses,
                                 DeviceRepository devices, Views views) {
        this.service = service;
        this.txs = txs;
        this.addresses = addresses;
        this.devices = devices;
        this.views = views;
    }

    public record LineRequest(@NotBlank String productId, @Min(1) @Max(10) int quantity) {}

    public record NewAddressRequest(@NotBlank String line1, String line2, String district, @NotBlank String city,
                                    String zip, @Pattern(regexp = "mine|other") String ownership) {}

    public record CheckoutRequest(@NotEmpty @Size(max = 20) List<@Valid LineRequest> items,
                                  String addressId,
                                  @Valid NewAddressRequest newAddress,
                                  String deviceId,
                                  @NotBlank @Pattern(regexp = "self|ad|link|guided", message = "Escolha como a compra começou") String declaredOrigin,
                                  @Size(max = 4000) String coachScript,
                                  String scenario) {}

    /**
     * Fecha o pedido. A resposta já traz a decisão: aprovada, aprovada com aviso, pedir contexto (com perguntas),
     * pausada ou bloqueada. Se o motor estiver fora, volta 202 com a compra em análise.
     */
    @PostMapping
    @Transactional
    public ResponseEntity<Views.CustomerTx> checkout(@Valid @RequestBody CheckoutRequest req) {
        CurrentUser u = Auth.customer();
        String addressId = resolveAddress(req, u.customerId());
        String deviceId = req.deviceId();
        if (deviceId == null || deviceId.isBlank())
            deviceId = devices.findByCustomerIdOrderByTrustedDescFirstSeenAsc(u.customerId()).stream().findFirst()
                    .orElseThrow(() -> ApiException.badRequest("Nenhum aparelho cadastrado")).getId();
        var draft = new TransactionService.Draft(u.customerId(), deviceId, addressId,
                req.items().stream().map(l -> new TransactionService.Line(l.productId(), l.quantity())).toList(),
                req.declaredOrigin(), req.coachScript(), req.scenario(), null, null, null, null, null, null);
        Transaction tx = service.create(draft, u.email());
        HttpStatus status = tx.getStatus() == com.contextshield.core.domain.TransactionStatus.ANALYZING ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(views.forCustomer(tx));
    }

    private String resolveAddress(CheckoutRequest req, String customerId) {
        if (req.addressId() != null && !req.addressId().isBlank()) {
            Address a = addresses.findById(req.addressId()).orElseThrow(() -> ApiException.badRequest("Endereço inválido"));
            boolean mine = a.getCustomerIds().contains(customerId);
            if (!mine && !"pickup".equals(a.getKind())) throw ApiException.badRequest("Escolha um endereço seu ou um ponto de retirada");
            return a.getId();
        }
        NewAddressRequest n = req.newAddress();
        if (n == null) throw ApiException.badRequest("Informe o endereço de entrega");
        // Se o texto bate com um endereço conhecido (ex.: um ponto de retirada), reaproveita: é o que liga casos diferentes.
        String typed = com.contextshield.core.service.IntelText.strip(n.line1());
        for (Address a : addresses.findAll())
            if (!typed.isEmpty() && typed.contains(com.contextshield.core.service.IntelText.strip(a.getLine1()))
                    && (a.getCustomerIds().isEmpty() || a.getCustomerIds().contains(customerId))) return a.getId();
        Address a = new Address();
        a.setId("ADDR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        boolean mine = !"other".equals(n.ownership());
        a.setLabel(mine ? "Novo endereço" : "Endereço de terceiro");
        a.setLine1(n.line1().trim());
        a.setLine2(n.line2());
        a.setDistrict(n.district());
        a.setCity(n.city());
        a.setZip(n.zip());
        a.setKind(mine ? "other" : "third");
        if (mine) a.getCustomerIds().add(customerId);
        return addresses.save(a).getId();
    }

    @GetMapping
    public List<Views.TxSummary> mine() {
        CurrentUser u = Auth.customer();
        return txs.findTop50ByCustomerIdOrderByCreatedAtDesc(u.customerId()).stream().map(views::summary).toList();
    }

    @GetMapping("/{id}")
    public Views.CustomerTx get(@PathVariable String id) {
        return views.forCustomer(service.forCustomer(id, Auth.user()));
    }

    public record VerificationRequest(@NotEmpty Map<String, String> answers) {}

    @PostMapping("/{id}/verification")
    public Views.CustomerTx verify(@PathVariable String id, @Valid @RequestBody VerificationRequest req) {
        return views.forCustomer(service.verify(id, req.answers(), Auth.user(), null));
    }

    public record ResolutionRequest(@NotBlank @Pattern(regexp = "cancel|proceed") String choice) {}

    @PostMapping("/{id}/resolution")
    public Views.CustomerTx resolve(@PathVariable String id, @Valid @RequestBody ResolutionRequest req) {
        return views.forCustomer(service.resolve(id, req.choice(), Auth.user(), null));
    }

    @PostMapping("/{id}/retry")
    public Views.CustomerTx retry(@PathVariable String id) {
        return views.forCustomer(service.retry(id, Auth.user()));
    }
}
