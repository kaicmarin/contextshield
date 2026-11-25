package com.contextshield.core.check;

import com.contextshield.core.service.IntelText;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntelTextTest {

    @Test
    void phoneEntityIdsNaoColidemComDddsDiferentes() {
        String a = IntelText.phoneEntityId("11987654321");
        String b = IntelText.phoneEntityId("21987654321");
        assertThat(a).isNotEqualTo(b);
        assertThat(a).isEqualTo("ENT-PHONE-11987654321");
    }

    @Test
    void domainEntityIdsPreservamDominioCompleto() {
        String a = IntelText.domainEntityId("loja.suspeita.example");
        String b = IntelText.domainEntityId("outro.suspeita.example");
        assertThat(a).isNotEqualTo(b);
    }
}