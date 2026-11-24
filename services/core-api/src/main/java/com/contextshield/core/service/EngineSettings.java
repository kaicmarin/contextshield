package com.contextshield.core.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flags do motor e situação das fontes de dados (up/down), guardadas no banco
 * para valerem em todas as réplicas da core-api.
 */
@Service
public class EngineSettings {
    public static final List<String> FLAGS = List.of("intelligence-matching", "dependency-discount",
            "context-on-declared-guidance", "guidance-floor", "script-mirror", "context-when-unknown");
    public static final List<String> PROVIDERS = List.of("history", "device", "merchant", "intelligence");

    private final JdbcTemplate jdbc;

    public EngineSettings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private Map<String, String> raw() {
        Map<String, String> m = new LinkedHashMap<>();
        jdbc.query("select setting_key, setting_value from engine_setting", rs -> {
            m.put(rs.getString(1), rs.getString(2));
        });
        return m;
    }

    public Map<String, Object> config() {
        Map<String, String> raw = raw();
        Map<String, Boolean> flags = new LinkedHashMap<>();
        for (String f : FLAGS) {
            String value = raw.get("flag." + f);
            boolean on = "context-when-unknown".equals(f) ? "true".equals(value) : !"false".equals(value);
            flags.put(f, on);
        }
        Map<String, String> providers = new LinkedHashMap<>();
        for (String p : PROVIDERS) providers.put(p, "down".equals(raw.get("provider." + p)) ? "down" : "up");
        return Map.of("flags", flags, "providers", providers);
    }

    public void setFlag(String flag, boolean on) {
        if (!FLAGS.contains(flag)) throw new IllegalArgumentException("Flag desconhecida: " + flag);
        put("flag." + flag, String.valueOf(on));
    }

    public void setProvider(String provider, boolean up) {
        if (!PROVIDERS.contains(provider)) throw new IllegalArgumentException("Fonte desconhecida: " + provider);
        put("provider." + provider, up ? "up" : "down");
    }

    public void reset() {
        jdbc.update("delete from engine_setting");
    }

    private void put(String key, String value) {
        int n = jdbc.update("update engine_setting set setting_value = ? where setting_key = ?", value, key);
        if (n == 0) jdbc.update("insert into engine_setting (setting_key, setting_value) values (?, ?)", key, value);
    }
}
