package com.contextshield.core.service;

import com.contextshield.core.TestSupport;
import com.contextshield.core.config.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrganizationServiceTest extends TestSupport {

    @Autowired OrganizationService organizations;
    @Autowired JdbcTemplate jdbc;

    @Test
    void blankEmailDoesNotReceiveDefaultOrganization() {
        assertThrows(RuntimeException.class, () -> organizations.idOfEmail(""));
        assertThrows(RuntimeException.class, () -> organizations.idOfEmail(null));
    }

    @Test
    void unknownUserDoesNotReceiveDefaultOrganization() {
        assertThrows(RuntimeException.class, () -> organizations.idOfEmail("naoexiste@example.com"));
    }

    @Test
    void systemActorIsRejectedForOrganizationLookup() {
        assertThrows(RuntimeException.class, () -> organizations.idOfEmail("core-api"));
    }

    @Test
    void requireSameRejectsNullResourceOrganization() {
        CurrentUser user = new CurrentUser("analista@contextshield.example", "Analista", "ANALYST", null);
        assertThrows(RuntimeException.class, () -> organizations.requireSame(user, null, "Recurso"));
    }

    @Test
    void requireSameRejectsCrossOrganizationAccess() {
        jdbc.update("update app_user set organization_id = 2 where email = 'analista@contextshield.example'");
        try {
            CurrentUser user = new CurrentUser("analista@contextshield.example", "Analista", "ANALYST", null);
            assertThrows(RuntimeException.class, () -> organizations.requireSame(user, 1L, "Política"));
        } finally {
            jdbc.update("update app_user set organization_id = 1 where email = 'analista@contextshield.example'");
        }
    }

    @Test
    void registeredAnalystResolvesOrganizationOne() {
        assertEquals(1L, organizations.idOfEmail("analista@contextshield.example"));
    }
}