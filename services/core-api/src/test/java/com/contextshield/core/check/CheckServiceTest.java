package com.contextshield.core.check;

import com.contextshield.core.repo.MerchantRepository;
import com.contextshield.core.repo.SellerRepository;
import com.contextshield.core.service.CheckService;
import com.contextshield.core.service.IntelligenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CheckServiceTest {

    @Mock IntelligenceService intel;
    @Mock MerchantRepository merchants;
    @Mock SellerRepository sellers;
    CheckService check;

    @BeforeEach
    void setup() {
        when(merchants.findAll()).thenReturn(List.of());
        when(sellers.findAll()).thenReturn(List.of());
        when(intel.hintFor(anyString())).thenReturn(new IntelligenceService.IntelHint(0, null, null, null));
        when(intel.matchAddressText(anyString())).thenReturn(Optional.empty());
        check = new CheckService(intel, merchants, sellers);
    }

    @Test
    void chipDeEntregaNaoEVerde() {
        var r = check.analyze("Sua encomenda está retida. Pague a taxa de R$ 12,90 em bit.ly/entrega-retida hoje ou ela volta ao remetente.");
        assertThat(r.level()).isIn("RED", "YELLOW");
        assertThat(r.level()).isNotEqualTo("GREEN");
        assertThat(r.analyzedAt()).isNotBlank();
        assertThat(r.signals()).extracting(CheckService.Signal::family)
                .contains("delivery", "url-shortener");
        assertThat(r.unverified()).extracting(CheckService.Finding::label)
                .anyMatch(l -> l.toLowerCase().contains("encurt"));
    }

    @Test
    void canalOficialSemPistasEVerde() {
        var r = check.analyze("0800 000 0000");
        assertThat(r.level()).isEqualTo("GREEN");
        assertThat(r.verdict()).isEqualTo("baixo");
    }

    @Test
    void textoSemSinalConfiavelECinza() {
        var r = check.analyze("Oi! Seu pedido NEXA saiu para entrega e chega amanhã entre 9h e 18h.");
        assertThat(r.level()).isEqualTo("GRAY");
        assertThat(r.verdict()).isEqualTo("insuficiente");
    }

    @Test
    void dominioOficialSozinhoEVerde() {
        var r = check.analyze("Acompanhe em https://nexa.example/pedidos/1");
        assertThat(r.level()).isEqualTo("GREEN");
    }

    @Test
    void lookalikeNaoEVerde() {
        var r = check.analyze("Confirme em aureon-protecao.example");
        assertThat(r.level()).isIn("YELLOW", "RED");
        assertThat(r.signals()).extracting(CheckService.Signal::family).contains("url-similar");
    }

    @Test
    void falhaDaInteligenciaNaoDevolveVerde() {
        when(intel.matchAddressText(anyString())).thenThrow(new RuntimeException("indisponível"));
        var r = check.analyze("0800 000 0000");
        assertThat(r.level()).isNotEqualTo("GREEN");
        assertThat(r.unverified()).extracting(CheckService.Finding::label)
                .anyMatch(l -> l.contains("cruzar relatos"));
    }

    @Test
    void relatoNaoConfirmadoPuxaParaAtencao() {
        when(intel.hintFor(anyString())).thenReturn(new IntelligenceService.IntelHint(1, "UNVERIFIED", null, null));
        var r = check.analyze("Me liga no (11) 90000-2184");
        assertThat(r.level()).isEqualTo("YELLOW");
        assertThat(r.verdict()).isEqualTo("atencao");
    }

    @Test
    void indicadorConfirmadoPuxaParaAlto() {
        when(intel.hintFor(anyString())).thenReturn(new IntelligenceService.IntelHint(2, "CONFIRMED", null, null));
        var r = check.analyze("Me liga no (11) 90000-2184");
        assertThat(r.level()).isEqualTo("RED");
        assertThat(r.verdict()).isEqualTo("alto");
    }

    @Test
    void descartadoNaoConta() {
        when(intel.hintFor(anyString())).thenReturn(new IntelligenceService.IntelHint(0, "DISCARDED", null, null));
        var r = check.analyze("Me liga no (11) 90000-2184");
        assertThat(r.level()).isEqualTo("GRAY");
    }
}
