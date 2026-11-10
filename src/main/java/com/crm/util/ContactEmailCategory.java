package com.crm.util;

import com.crm.domain.Database;
import com.crm.domain.DatabaseEmail;
import com.crm.domain.DatabaseDestination;

import java.util.Locale;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class ContactEmailCategory {
    private static final Pattern EMAIL = Pattern.compile("(?U)^[^@\\s,;]+@[^@\\s,;]+\\.[^@\\s,;]+$");
    private static final Set<String> PERSONAL_DOMAINS = Set.of(
            "gmail.com", "googlemail.com",
            "yahoo.com", "yahoo.co.id", "yahoo.co.uk", "ymail.com", "rocketmail.com",
            "hotmail.com", "hotmail.co.id", "outlook.com", "outlook.co.id",
            "live.com", "live.co.id", "windowslive.com", "msn.com",
            "icloud.com", "me.com", "mac.com",
            "aol.com", "mail.com", "zoho.com", "proton.me", "protonmail.com"
    );

    private ContactEmailCategory() {}

    public static boolean isValidEmail(String email) {
        return email != null && EMAIL.matcher(email.trim()).matches();
    }

    public static List<String> splitEmails(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.toLowerCase(Locale.ROOT).split("(?U)[,;/\\s]+"))
                .filter(token -> !token.isBlank()).distinct().toList();
    }

    public static boolean isPublicPersonalEmail(String email) {
        if (email == null || !email.contains("@")) return false;
        String domain = email.substring(email.indexOf('@') + 1).trim().toLowerCase(Locale.ROOT);
        return PERSONAL_DOMAINS.contains(domain);
    }

    public static boolean isPersonalEmail(String email) {
        return isValidEmail(email) && isPublicPersonalEmail(email);
    }

    public static boolean isParticipantOnly(Database database) {
        return database != null && DatabaseDestination.effective(database.getDatabaseDestination())
                == DatabaseDestination.PARTICIPANT;
    }

    public static boolean hasValidEmail(Database database) {
        return database != null && database.getEmails() != null && database.getEmails().stream()
                .filter(Objects::nonNull).anyMatch(email -> isValidEmail(email.getEmail()));
    }

    public static boolean isCompanyEmail(DatabaseEmail email) {
        return email != null && isValidEmail(email.getEmail()) && !isPublicPersonalEmail(email.getEmail())
                && (Boolean.TRUE.equals(email.getIsCorporate()) || "company".equalsIgnoreCase(email.getEmailType()));
    }
}
