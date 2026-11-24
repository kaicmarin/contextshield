package com.contextshield.core.web;

import com.contextshield.core.config.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Atalho para pegar o usuário autenticado dentro dos controllers. */
public final class Auth {
    private Auth() {}

    public static CurrentUser user() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !(a.getPrincipal() instanceof CurrentUser u)) throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Faça login para continuar");
        return u;
    }

    public static CurrentUser customer() {
        CurrentUser u = user();
        if (u.customerId() == null) throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "Esta ação é só para clientes");
        return u;
    }
}
