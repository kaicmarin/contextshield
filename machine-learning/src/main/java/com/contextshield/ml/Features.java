package com.contextshield.ml;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Características disponíveis antes da decisão. Não entra score, regra nem resposta da verificação:
 * a resposta só existe depois que o motor já pediu contexto.
 */
public final class Features {
    private Features() {}

    public static final String[] NAMES = {
            "amountRatio", "purchases90d", "tenureYears", "unusualHour",
            "newDevice", "locationMatch", "habitualDest", "newOwnDest", "thirdDest", "pickup",
            "sellerRecent", "sellerClaims", "sellerEstablished",
            "originSelf", "originAd", "originLink", "originGuided",
            "destIncident", "sellerIncident"
    };

    public static double[] vector(Map<String, Object> checkout) {
        if (checkout == null) throw new IllegalArgumentException("Compra sem dados.");
        Object amountValue = checkout.get("amount");
        Object customerValue = checkout.get("customer");
        Object atValue = checkout.get("at");
        if (!(amountValue instanceof Number amount) || !(customerValue instanceof Map<?, ?> customer) || atValue == null) {
            throw new IllegalArgumentException("Faltam valor, cliente ou horário.");
        }
        double avg = number(customer.get("avgTicket90d"), 0);
        if (avg <= 0) throw new IllegalArgumentException("Ticket médio ausente.");
        LocalDateTime at = time(atValue);
        if (at == null) throw new IllegalArgumentException("Horário ilegível.");

        double ratio = Math.min(amount.doubleValue() / avg, 8) / 4.0;
        double purchases = Math.min(number(customer.get("purchases90d"), 0), 40) / 20.0;
        LocalDate since = date(customer.get("since"));
        double tenure = since == null ? 0 : Math.min(ChronoUnit.DAYS.between(since, at.toLocalDate()) / 365.0, 10) / 5.0;
        int hour = at.getHour();
        double unusual = (hour >= 23 || hour < 6) ? 1 : 0;

        Map<?, ?> device = map(checkout.get("device"));
        boolean trusted = device != null && Boolean.TRUE.equals(device.get("trusted"));
        String city = text(customer.get("city"));
        String location = device == null ? "" : text(device.get("location"));
        double locationMatch = !trusted || city.isBlank() || location.isBlank() ? 0 : location.startsWith(city) ? 1 : 0;

        Map<?, ?> address = map(checkout.get("address"));
        boolean owned = containsCustomer(address == null ? null : address.get("customerIds"), text(customer.get("id")));
        boolean used = Boolean.TRUE.equals(checkout.get("addressUsedBefore"));
        String kind = address == null ? "" : text(address.get("kind"));
        double habitual = owned && (used || "home".equals(kind)) ? 1 : 0;
        double newOwn = owned && habitual == 0 ? 1 : 0;
        double third = owned ? 0 : 1;
        double pickup = !owned && "pickup".equals(kind) ? 1 : 0;

        SellerFacts seller = sellers(checkout.get("sellers"), at);
        String origin = text(checkout.get("declaredOrigin"));
        boolean destIncident = false;
        boolean sellerIncident = false;
        if (checkout.get("relations") instanceof List<?> relations) {
            for (Object item : relations) {
                Map<?, ?> relation = map(item);
                if (relation == null) continue;
                if ("destination".equals(relation.get("kind"))) destIncident = true;
                if ("seller".equals(relation.get("kind")) && Boolean.TRUE.equals(relation.get("crossCustomer"))
                        && !"não indicativa".equals(relation.get("association"))) sellerIncident = true;
            }
        }

        double[] row = {
                ratio, purchases, tenure, unusual,
                trusted ? 0 : 1, locationMatch, habitual, newOwn, third, pickup,
                seller.recent, seller.claims, seller.established,
                "self".equals(origin) ? 1 : 0, "ad".equals(origin) ? 1 : 0,
                "link".equals(origin) ? 1 : 0, "guided".equals(origin) ? 1 : 0,
                destIncident ? 1 : 0, sellerIncident ? 1 : 0
        };
        if (row.length != NAMES.length) throw new IllegalStateException("Vetor fora do catálogo.");
        for (double value : row) {
            if (Double.isNaN(value) || Double.isInfinite(value)) throw new IllegalArgumentException("Característica inválida.");
        }
        return row;
    }

    private record SellerFacts(double recent, double claims, double established) {}

    private static SellerFacts sellers(Object raw, LocalDateTime at) {
        if (!(raw instanceof List<?> sellers) || sellers.isEmpty()) return new SellerFacts(0, 0, 0);
        boolean recent = false;
        boolean claims = false;
        boolean established = true;
        for (Object item : sellers) {
            Map<?, ?> seller = map(item);
            if (seller == null) continue;
            LocalDate since = date(seller.get("since"));
            long days = since == null ? 0 : ChronoUnit.DAYS.between(since, at.toLocalDate());
            if (days < 60) recent = true;
            if (number(seller.get("claimsRate"), 0) >= 3) claims = true;
            boolean old = days > 2 * 365 && number(seller.get("rating"), 0) >= 4.5 && !"novo".equals(seller.get("tier"));
            if (!old) established = false;
        }
        if (recent || claims) established = false;
        return new SellerFacts(recent ? 1 : 0, claims ? 1 : 0, established ? 1 : 0);
    }

    private static boolean containsCustomer(Object raw, String id) {
        if (!(raw instanceof List<?> ids) || id.isBlank()) return false;
        for (Object item : ids) if (id.equals(String.valueOf(item))) return true;
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : null;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private static LocalDateTime time(Object value) {
        if (value instanceof LocalDateTime time) return time;
        if (value instanceof String text && !text.isBlank()) return LocalDateTime.parse(text);
        return null;
    }

    private static LocalDate date(Object value) {
        if (value instanceof LocalDate date) return date;
        if (value instanceof LocalDateTime time) return time.toLocalDate();
        if (value instanceof String text && text.length() >= 10) return LocalDate.parse(text.substring(0, 10));
        return null;
    }
}
