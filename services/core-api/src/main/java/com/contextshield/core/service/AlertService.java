package com.contextshield.core.service;

import com.contextshield.core.domain.Alert;
import com.contextshield.core.domain.Transaction;
import com.contextshield.core.domain.TransactionStatus;
import com.contextshield.core.repo.AlertRepository;
import org.springframework.stereotype.Service;

import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.util.Locale;

/** Avisos do app do banco. Cada decisão de proteção gera uma mensagem em linguagem simples. */
@Service
public class AlertService {

    private final AlertRepository repo;

    public AlertService(AlertRepository repo) {
        this.repo = repo;
    }

    static String brl(Number v) {
        return NumberFormat.getCurrencyInstance(Locale.of("pt", "BR")).format(v).replace(' ', ' ');
    }

    public Alert create(String customerId, String txId, String kind, String title, String body, LocalDateTime at) {
        Alert a = new Alert();
        a.setCustomerId(customerId);
        a.setTxId(txId);
        a.setKind(kind);
        a.setTitle(title);
        a.setBody(body);
        a.setCreatedAt(at);
        return repo.save(a);
    }

    public void onStatus(Transaction tx, TransactionStatus status, LocalDateTime at) {
        String value = brl(tx.getAmount());
        String what = tx.getDescription() == null ? "sua compra" : tx.getDescription();
        switch (status) {
            case APPROVED_WITH_ALERT -> create(tx.getCustomerId(), tx.getId(), "warning", "Compra aprovada com aviso",
                    "Aprovamos " + what + " de " + value + ", mas alguns detalhes fogem do seu padrão. Se alguém pediu esta compra por telefone ou mensagem, fale com a central oficial: "
                            + IntelText.OFFICIAL_CENTRAL + ".", at);
            case CONTEXT_REQUIRED -> create(tx.getCustomerId(), tx.getId(), "action", "Confirme uma compra",
                    "Antes de concluir " + what + " de " + value + ", precisamos de algumas respostas rápidas na tela da loja.", at);
            case INTERVENTION -> create(tx.getCustomerId(), tx.getId(), "danger", "Compra pausada para sua proteção",
                    "Pausamos " + what + " de " + value + ". O banco nunca pede compras para proteger o seu cartão. Se alguém está orientando você agora, desligue e ligue para "
                            + IntelText.OFFICIAL_CENTRAL + ".", at);
            case BLOCKED -> create(tx.getCustomerId(), tx.getId(), "danger", "Compra interrompida",
                    "Interrompemos " + what + " de " + value + " porque o destino ou o vendedor aparecem em relatos de golpe de outros clientes. Nenhum valor foi cobrado.", at);
            default -> { }
        }
    }
}
