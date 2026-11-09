package com.crm.util;

import java.util.*;
import java.util.stream.Collectors;

/** Phone lists keep separate numbers; identity and primary phone use normalized individual numbers. */
public final class ContactPhone {
    private ContactPhone() {}

    public static List<String> tokens(String value) {
        return value == null || value.isBlank() ? List.of()
                : Arrays.stream(value.trim().split("[,;/|\\r\\n]+", -1)).map(String::trim).toList();
    }

    public static boolean isUsable(String value) {
        List<String> phones = tokens(value);
        return !phones.isEmpty() && phones.stream().allMatch(phone -> {
            String digits = phone.replaceAll("[^0-9]", "");
            return phone.matches("\\+?[0-9() .\\-]+") && digits.length() >= 6 && digits.length() <= 15
                    && !normalizeSingle(phone).isBlank();
        });
    }

    public static Set<String> normalizedNumbers(String value) {
        return !isUsable(value) ? new LinkedHashSet<>() : tokens(value).stream().map(ContactPhone::normalizeSingle)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public static String primary(String value) {
        return normalizedNumbers(value).stream().findFirst().orElse("");
    }

    public static String clean(String value) {
        return tokens(value).stream().map(phone -> phone.replaceAll("[^0-9+]", "")).collect(Collectors.joining(", "));
    }

    public static String merge(String existing, String incoming) {
        if (existing != null && !existing.isBlank() && !isUsable(existing)) {
            throw new IllegalArgumentException("Nomor HP tersimpan tidak valid; rapikan profil melalui Edit kontak sebelum import.");
        }
        if (!isUsable(incoming)) throw new IllegalArgumentException("Mobile Phone tidak valid.");
        Map<String, String> phones = new LinkedHashMap<>();
        for (String value : List.of(Objects.toString(existing, ""), incoming)) for (String phone : tokens(value)) {
            phones.putIfAbsent(normalizeSingle(phone), phone.replaceAll("[^0-9+]", ""));
        }
        String merged = String.join(", ", phones.values());
        if (merged.length() > 50) throw new IllegalArgumentException("Mobile Phone maksimal 50 karakter setelah nomor digabungkan.");
        return merged;
    }

    private static String normalizeSingle(String original) {
        String digits = original.replaceAll("[^0-9]", "");
        if (digits.isEmpty() || digits.matches("0+") || digits.equals("62")) return "";
        if (original.startsWith("+")) return digits;
        if (original.startsWith("00")) return digits.substring(2);
        if (digits.startsWith("0")) return "62" + digits.substring(1);
        if (digits.startsWith("8")) return "62" + digits;
        return digits;
    }
}
