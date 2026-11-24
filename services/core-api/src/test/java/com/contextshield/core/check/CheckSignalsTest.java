package com.contextshield.core.check;

import com.contextshield.core.service.CheckSignals;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Conjunto de desenvolvimento: variações usadas para ajustar famílias. O holdout está em check-eval.json. */
class CheckSignalsTest {

    private static Set<String> families(String text) {
        return CheckSignals.detect(text).stream().map(CheckSignals.Hit::family).collect(Collectors.toSet());
    }

    @Test
    void encomendaRetidaNaoDependeDaOrdemExata() {
        assertThat(families("Sua encomenda está retida. Pague a taxa de R$ 12,90 hoje."))
                .contains("delivery", "payment", "urgency");
        assertThat(families("Pacote parado na alfândega, pague para liberar."))
                .contains("delivery", "payment");
    }

    @Test
    void umPontoPorFamilia() {
        var hits = CheckSignals.detect("Encomenda retida. Pacote parado. Taxa de entrega. Pague a taxa.");
        assertThat(hits.stream().filter(h -> "delivery".equals(h.family()))).hasSize(1);
    }

    @Test
    void outrasFamiliasNoTextoNormalizado() {
        assertThat(families("Aqui é da central de segurança. Compra não reconhecida."))
                .contains("bank");
        assertThat(families("Mãe, troquei de número, preciso de um pix urgente."))
                .contains("relative", "payment", "urgency");
        assertThat(families("Você ganhou um prêmio. Resgate agora."))
                .contains("prize");
        assertThat(families("Suporte técnico Microsoft: instale o AnyDesk e informe a senha."))
                .contains("support", "code");
        assertThat(families("Deposite um adiantamento para liberar o valor."))
                .contains("advance");
        assertThat(families("Clique no link para atualizar seus dados e evitar o bloqueio."))
                .contains("validation");
        assertThat(families("Receita Federal: regularize seu CPF. Pague a multa por pix."))
                .contains("authority", "payment");
        assertThat(families("Renda extra: tarefas pagas, ganhe por dia."))
                .contains("job");
    }

    @Test
    void mensagemLegitimaDeEntregaNaoDisparaEntrega() {
        assertThat(families("Oi! Seu pedido NEXA saiu para entrega e chega amanhã entre 9h e 18h."))
                .doesNotContain("delivery", "payment", "urgency");
    }
}
