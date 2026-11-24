package com.contextshield.core.persistence;

import com.contextshield.core.TestSupport;
import com.contextshield.core.domain.*;
import com.contextshield.core.repo.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Teste de persistência: CRUD direto no banco (usuários, transações, avisos) e as regras de
 * integridade que o esquema garante (chave estrangeira, unicidade, CHECK).
 */
class PersistenceTest extends TestSupport {

    @Autowired AppUserRepository users;
    @Autowired TransactionRepository txs;
    @Autowired TransactionItemRepository items;
    @Autowired AlertRepository alerts;
    @Autowired JdbcTemplate jdbc;

    @Test
    void crudDeUsuario() {
        AppUser u = new AppUser();
        u.setEmail("crud@contextshield.example");
        u.setName("Crud");
        u.setPasswordHash("x");
        u.setRole(AppUser.Role.ANALYST);
        u.setCreatedAt(LocalDateTime.now());
        u = users.save(u);
        assertThat(users.findByEmailIgnoreCase("CRUD@contextshield.example")).isPresent();
        u.setName("Crud alterado");
        users.save(u);
        assertThat(users.findById(u.getId()).orElseThrow().getName()).isEqualTo("Crud alterado");
        users.delete(u);
        assertThat(users.findById(u.getId())).isEmpty();
    }

    private Transaction newTx(String id, BigDecimal amount) {
        Transaction t = new Transaction();
        t.setId(id);
        t.setCustomerId("CUST-001");
        t.setMerchantId("MER-001");
        t.setDeviceId("DEV-ANA-MBA");
        t.setAddressId("ADDR-ANA-HOME");
        t.setAmount(amount);
        t.setStatus(TransactionStatus.RECEIVED);
        t.setCreatedAt(LocalDateTime.now());
        t.setUpdatedAt(LocalDateTime.now());
        return t;
    }

    @Test
    void crudDeTransacaoComItensEmCascata() {
        Transaction t = txs.save(newTx("TX-TEST-000001", new BigDecimal("99.90")));
        TransactionItem it = new TransactionItem();
        it.setTxId(t.getId());
        it.setName("Mouse");
        it.setQuantity(1);
        it.setUnitPrice(new BigDecimal("99.90"));
        items.save(it);
        assertThat(items.findByTxId(t.getId())).hasSize(1);

        t.setStatus(TransactionStatus.ANALYZING);
        txs.save(t);
        assertThat(txs.findById(t.getId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.ANALYZING);
        assertThat(txs.findById(t.getId()).orElseThrow().getVersion()).isEqualTo(1);

        jdbc.update("delete from transactions where id = ?", t.getId());
        assertThat(txs.findById(t.getId())).isEmpty();
        assertThat(items.findByTxId(t.getId())).as("ON DELETE CASCADE nos itens").isEmpty();
    }

    @Test
    void crudDeAviso() {
        Alert a = new Alert();
        a.setCustomerId("CUST-002");
        a.setKind("info");
        a.setTitle("Olá");
        a.setBody("Corpo");
        a.setCreatedAt(LocalDateTime.now());
        a = alerts.save(a);
        a.setReadAt(LocalDateTime.now());
        alerts.save(a);
        assertThat(alerts.findById(a.getId()).orElseThrow().getReadAt()).isNotNull();
        alerts.delete(a);
        assertThat(alerts.findById(a.getId())).isEmpty();
    }

    @Test
    void bancoRecusaChaveEstrangeiraInexistente() {
        Transaction t = newTx("TX-TEST-000002", BigDecimal.TEN);
        t.setCustomerId("CUST-NAO-EXISTE");
        assertThatThrownBy(() -> txs.saveAndFlush(t)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void bancoRecusaValorNegativoEEmailDuplicado() {
        assertThatThrownBy(() -> txs.saveAndFlush(newTx("TX-TEST-000003", new BigDecimal("-1"))))
                .isInstanceOf(DataIntegrityViolationException.class);
        AppUser dup = new AppUser();
        dup.setEmail("ana.martins@correio.example");
        dup.setName("Dup");
        dup.setPasswordHash("x");
        dup.setRole(AppUser.Role.CUSTOMER);
        dup.setCreatedAt(LocalDateTime.now());
        assertThatThrownBy(() -> users.saveAndFlush(dup)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void historicoDeStatusFicaConsistenteComAsAvaliacoes() {
        // Toda compra avaliada tem: 1 avaliação por estágio e o último status do histórico igual ao status atual.
        List<String> inconsistent = jdbc.queryForList("""
                select t.id from transactions t
                where t.status <> (select s.to_status from status_change s where s.tx_id = t.id
                                   and s.id = (select max(s2.id) from status_change s2 where s2.tx_id = t.id))""", String.class);
        assertThat(inconsistent).isEmpty();
        Long orphans = jdbc.queryForObject("select count(*) from assessment a left join transactions t on t.id = a.tx_id where t.id is null", Long.class);
        assertThat(orphans).isZero();
    }
}
