package com.contextshield.core.check;

import com.contextshield.core.service.CheckUrl;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CheckUrlTest {

    private static final Set<String> OFF = Set.of("nexa.example", "aureon.example");

    @Test
    void extraiEncurtadorSemAbrir() {
        var found = CheckUrl.extract("Pague em bit.ly/entrega-retida hoje.", OFF);
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().kind()).isEqualTo(CheckUrl.Kind.SHORTENER);
        assertThat(found.getFirst().host()).isEqualTo("bit.ly");
        assertThat(found.getFirst().notes()).isNotEmpty();
    }

    @Test
    void classificaOficialSemelhanteEDesconhecido() {
        assertThat(CheckUrl.classify("https://www.nexa.example/pedido/1", OFF).kind()).isEqualTo(CheckUrl.Kind.OFFICIAL);
        assertThat(CheckUrl.classify("aureon-protecao.example", OFF).kind()).isEqualTo(CheckUrl.Kind.SIMILAR);
        assertThat(CheckUrl.classify("login-nexa.com", OFF).kind()).isEqualTo(CheckUrl.Kind.SIMILAR);
        assertThat(CheckUrl.classify("loja-desconhecida.com.br/x", OFF).kind()).isEqualTo(CheckUrl.Kind.UNKNOWN);
        assertThat(CheckUrl.classify("t.co/abc", OFF).kind()).isEqualTo(CheckUrl.Kind.SHORTENER);
        assertThat(CheckUrl.classify("cutt.ly/pacote", OFF).kind()).isEqualTo(CheckUrl.Kind.SHORTENER);
        var ip = CheckUrl.classify("http://203.0.113.10/login", OFF);
        assertThat(ip.host()).isEqualTo("203.0.113.10");
        assertThat(ip.notes()).anyMatch(n -> n.contains("IP"));
        var trap = CheckUrl.classify("http://aureon.example@evil.com/login", OFF);
        assertThat(trap.host()).isEqualTo("evil.com");
        assertThat(trap.notes()).anyMatch(n -> n.contains("@"));
    }

    @Test
    void normalizaEsquemaPontuacaoEComBr() {
        assertThat(CheckUrl.hostOf("NEXA.EXAMPLE/loja")).isEqualTo("nexa.example");
        assertThat(CheckUrl.hostOf("http://www.loja.com.br/x.")).isEqualTo("loja.com.br");
        assertThat(CheckUrl.registrable("pag.loja.com.br")).isEqualTo("loja.com.br");
        assertThat(CheckUrl.extract("veja loja.example).", OFF)).extracting(CheckUrl.Extracted::host)
                .containsExactly("loja.example");
    }

    @Test
    void detectaParecidoEPunycode() {
        assertThat(CheckUrl.close("aure0n", "aureon")).isTrue();
        assertThat(CheckUrl.foldConfusable("aure0n")).isEqualTo("aureon");
        var puny = CheckUrl.classify("xn--nexa-something.example", OFF);
        assertThat(puny).isNotNull();
        assertThat(puny.notes().stream().anyMatch(n -> n.toLowerCase().contains("punycode"))).isTrue();
    }
}
