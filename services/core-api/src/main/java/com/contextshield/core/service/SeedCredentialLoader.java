package com.contextshield.core.service;

import com.contextshield.core.config.AppProperties;
import com.contextshield.core.security.PasswordPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lê senhas iniciais do seed a partir de arquivo (variável de ambiente), nunca do código ou Git com valores reais.
 * Cada e-mail da demonstração precisa de uma entrada própria; ausência falha de forma explícita.
 */
@Service
public class SeedCredentialLoader {

    private final AppProperties props;
    private final ResourceLoader resources;
    private final ObjectMapper json;
    private volatile Map<String, String> cache;

    public SeedCredentialLoader(AppProperties props, ResourceLoader resources, ObjectMapper json) {
        this.props = props;
        this.resources = resources;
        this.json = json;
    }

    public String passwordFor(String email) {
        return passwords().get(normalize(email));
    }

    public Map<String, String> passwordsFor(Set<String> emails) {
        Map<String, String> all = passwords();
        Map<String, String> out = new LinkedHashMap<>();
        for (String email : emails) {
            String key = normalize(email);
            String pw = all.get(key);
            if (pw == null || pw.isBlank()) {
                throw new SeedCredentialsException("Credencial de seed ausente para o e-mail: " + key);
            }
            PasswordPolicy.requireNewPassword(pw);
            out.put(key, pw);
        }
        return out;
    }

    private Map<String, String> passwords() {
        Map<String, String> c = cache;
        if (c != null) return c;
        synchronized (this) {
            if (cache != null) return cache;
            cache = load();
            return cache;
        }
    }

    private Map<String, String> load() {
        String path = props.seed().credentialsFile();
        if (path == null || path.isBlank()) {
            throw new SeedCredentialsException(
                    "Defina contextshield.seed.credentials-file (ex.: SEED_CREDENTIALS_FILE) com um JSON de senhas por e-mail antes de popular o banco vazio.");
        }
        Resource resource = resources.getResource(path);
        if (!resource.exists()) {
            throw new SeedCredentialsException("Arquivo de credenciais de seed não encontrado: " + path);
        }
        try (InputStream in = resource.getInputStream()) {
            JsonNode root = json.readTree(in);
            JsonNode passwords = root.path("passwords");
            if (!passwords.isObject() || passwords.isEmpty()) {
                throw new SeedCredentialsException("O arquivo de seed precisa de um objeto \"passwords\" com um e-mail por conta.");
            }
            Map<String, String> out = new LinkedHashMap<>();
            passwords.fields().forEachRemaining(e -> {
                String mail = normalize(e.getKey());
                String value = e.getValue().asText("").trim();
                if (value.isBlank()) {
                    throw new SeedCredentialsException("Senha vazia no seed para: " + mail);
                }
                out.put(mail, value);
            });
            return Map.copyOf(out);
        } catch (IOException e) {
            throw new SeedCredentialsException("Não foi possível ler as credenciais de seed: " + e.getMessage());
        }
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    public static class SeedCredentialsException extends IllegalStateException {
        public SeedCredentialsException(String message) {
            super(message);
        }
    }
}