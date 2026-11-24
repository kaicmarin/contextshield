package com.contextshield.risk.engine;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;

/** Formatação em português para os textos que o cliente e o analista leem. */
final class Fmt {
    private Fmt() {}

    private static final Locale PT_BR = Locale.of("pt", "BR");
    private static final String[] MONTHS = {"jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez"};

    static String brl(double value) {
        return NumberFormat.getCurrencyInstance(PT_BR).format(value).replace(' ', ' ');
    }

    static String date(LocalDate d) {
        if (d == null) return "—";
        return String.format("%02d %s %d", d.getDayOfMonth(), MONTHS[d.getMonthValue() - 1], d.getYear());
    }

    static String date(LocalDateTime d) {
        return d == null ? "—" : date(d.toLocalDate());
    }

    static String ratio(double r) {
        return String.format(Locale.ROOT, "%.1f", r).replace('.', ',') + "×";
    }

    static String decimal(double v) {
        String s = (v == Math.rint(v)) ? String.valueOf((long) v) : String.valueOf(v);
        return s.replace('.', ',');
    }
}
