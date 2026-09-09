package com.crm.service;

import com.crm.domain.*;
import com.crm.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class SuspiciousIdentityService {

    @Autowired
    private FlaggedIdentityRepository flaggedIdentityRepository;

    @Autowired
    private DatabaseRepository databaseRepository;

    @Autowired
    private DatabaseEmailRepository databaseEmailRepository;

    public void checkAndFlagDatabase(Database database) {
        if (database == null || database.getId() == null) {
            return;
        }

        // 1. Auto-flag duplicate phone with different name
        String phone = database.getMobilePhone();
        if (phone != null && !phone.trim().isEmpty()) {
            String digits = phone.trim().replaceAll("[^0-9]", "");
            if (digits.startsWith("62")) {
                digits = digits.substring(2);
            } else if (digits.startsWith("0")) {
                digits = digits.substring(1);
            }
            if (!digits.isEmpty()) {
                String normalized = "+62" + digits;
                List<Database> matchedDatabases = databaseRepository.findByNormalizedPhone(normalized);

                for (Database other : matchedDatabases) {
                    if (other.getId().equals(database.getId())) {
                        continue;
                    }
                    if (sameCompany(database, other)) {
                        continue;
                    }

                    // If names are different
                    if (!other.getFirstName().equalsIgnoreCase(database.getFirstName()) ||
                            !other.getLastName().equalsIgnoreCase(database.getLastName())) {

                        // Flag the current database record
                        boolean databaseAlreadyFlagged = flaggedIdentityRepository.existsByDatabaseIdAndFlagReasonAndStatusNot(
                                database.getId(), FlagReason.duplicate_phone, FlagStatus.cleared);

                        if (!databaseAlreadyFlagged) {
                            FlaggedIdentity flag = FlaggedIdentity.builder()
                                    .database(database)
                                    .nameUsed(database.getFirstName() + " " + database.getLastName())
                                    .phoneUsed(phone)
                                    .flagReason(FlagReason.duplicate_phone)
                                    .status(FlagStatus.suspected)
                                    .evidenceNotes(
                                            String.format("Auto-flagged: Phone number %s matches database record %s %s (ID: %s)",
                                                    phone, other.getFirstName(), other.getLastName(), other.getId()))
                                    .build();
                            flaggedIdentityRepository.save(flag);
                        }

                        // Flag the other database record as well
                        boolean otherAlreadyFlagged = flaggedIdentityRepository.existsByDatabaseIdAndFlagReasonAndStatusNot(
                                other.getId(), FlagReason.duplicate_phone, FlagStatus.cleared);

                        if (!otherAlreadyFlagged) {
                            FlaggedIdentity flag = FlaggedIdentity.builder()
                                    .database(other)
                                    .nameUsed(other.getFirstName() + " " + other.getLastName())
                                    .phoneUsed(phone)
                                    .flagReason(FlagReason.duplicate_phone)
                                    .status(FlagStatus.suspected)
                                    .evidenceNotes(
                                            String.format("Auto-flagged: Phone number %s matches database record %s %s (ID: %s)",
                                                    phone, database.getFirstName(), database.getLastName(), database.getId()))
                                    .build();
                            flaggedIdentityRepository.save(flag);
                        }
                    }
                }
            }
        }

        // 2. Auto-flag duplicate email with different name
        List<DatabaseEmail> databaseEmails = database.getEmails();
        if (databaseEmails != null) {
            for (DatabaseEmail ce : databaseEmails) {
                String emailStr = normalizeField(ce.getEmail());
                if (!emailStr.isEmpty() && "personal".equalsIgnoreCase(ce.getEmailType())) {
                    databaseEmailRepository.findAllByEmailIgnoreCase(emailStr).stream()
                            .filter(otherEmail -> otherEmail.getDatabase() != null
                                    && !otherEmail.getDatabase().getId().equals(database.getId()))
                            .findFirst()
                            .ifPresent(otherEmail -> {
                        if (otherEmail.getDatabase() != null && !otherEmail.getDatabase().getId().equals(database.getId())) {
                            Database other = otherEmail.getDatabase();

                            // If names are different
                            if (!other.getFirstName().equalsIgnoreCase(database.getFirstName()) ||
                                    !other.getLastName().equalsIgnoreCase(database.getLastName())) {

                                // Flag the current database record
                                boolean databaseAlreadyFlagged = flaggedIdentityRepository.existsByDatabaseIdAndFlagReasonAndStatusNot(
                                        database.getId(), FlagReason.duplicate_email, FlagStatus.cleared);

                                if (!databaseAlreadyFlagged) {
                                    FlaggedIdentity flag = FlaggedIdentity.builder()
                                            .database(database)
                                            .nameUsed(database.getFirstName() + " " + database.getLastName())
                                            .emailUsed(emailStr)
                                            .flagReason(FlagReason.duplicate_email)
                                            .status(FlagStatus.suspected)
                                            .evidenceNotes(
                                                    String.format("Auto-flagged: Email %s matches database record %s %s (ID: %s)",
                                                            emailStr, other.getFirstName(), other.getLastName(), other.getId()))
                                            .build();
                                    flaggedIdentityRepository.save(flag);
                                }

                                // Flag the other database record as well
                                boolean otherAlreadyFlagged = flaggedIdentityRepository.existsByDatabaseIdAndFlagReasonAndStatusNot(
                                        other.getId(), FlagReason.duplicate_email, FlagStatus.cleared);

                                if (!otherAlreadyFlagged) {
                                    FlaggedIdentity flag = FlaggedIdentity.builder()
                                            .database(other)
                                            .nameUsed(other.getFirstName() + " " + other.getLastName())
                                            .emailUsed(emailStr)
                                            .flagReason(FlagReason.duplicate_email)
                                            .status(FlagStatus.suspected)
                                            .evidenceNotes(String.format(
                                                    "Auto-flagged: Email %s matches database record %s %s (ID: %s)",
                                                    emailStr, database.getFirstName(), database.getLastName(), database.getId()))
                                            .build();
                                    flaggedIdentityRepository.save(flag);
                                }
                            }
                        }
                    });
                }
            }
        }

        // 3. Auto-flag and auto-link against existing manual FlaggedIdentity entries (by matching phone digits or email)
        List<FlaggedIdentity> allFlags = flaggedIdentityRepository.findAll();
        String dbPhoneDigits = extractSubscriberDigits(database.getMobilePhone());

        for (FlaggedIdentity flag : allFlags) {
            if (flag.getStatus() == FlagStatus.cleared) {
                continue;
            }

            boolean phoneMatch = false;
            if (flag.getPhoneUsed() != null && !dbPhoneDigits.isEmpty()) {
                String flagDigits = extractSubscriberDigits(flag.getPhoneUsed());
                if (!flagDigits.isEmpty() && flagDigits.equals(dbPhoneDigits)) {
                    phoneMatch = true;
                }
            }

            boolean emailMatch = false;
            if (flag.getEmailUsed() != null && !flag.getEmailUsed().trim().isEmpty() && database.getEmails() != null) {
                String flagEmail = flag.getEmailUsed().trim().toLowerCase();
                emailMatch = database.getEmails().stream()
                        .anyMatch(e -> e.getEmail() != null && e.getEmail().trim().equalsIgnoreCase(flagEmail));
            }

            if (phoneMatch || emailMatch) {
                if (flag.getDatabase() == null) {
                    flag.setDatabase(database);
                    flaggedIdentityRepository.save(flag);
                }
            }
        }
    }

    private boolean sameCompany(Database first, Database second) {
        return first.getCompany() != null && second.getCompany() != null
                && first.getCompany().getId() != null
                && first.getCompany().getId().equals(second.getCompany().getId());
    }

    public String extractSubscriberDigits(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return "";
        String digits = trimmed.replaceAll("[^0-9]", "");
        if (digits.startsWith("62")) {
            digits = digits.substring(2);
        } else if (digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        return digits;
    }

    /**
     * Normalizes a field value to empty string if it is a placeholder sentinel
     * such as "-", "--", "N/A", "na", "none", "null".
     * Prevents placeholders from being treated as real phone/email data
     * and causing false tikus flags.
     */
    private String normalizeField(String value) {
        if (value == null)
            return "";
        String trimmed = value.trim();
        if (trimmed.isEmpty())
            return "";
        // Strings that are only dashes/hyphens (e.g. "-", "--", "---")
        if (trimmed.matches("^-+$"))
            return "";
        // Common placeholder literals (case-insensitive)
        switch (trimmed.toLowerCase()) {
            case "n/a":
            case "na":
            case "none":
            case "null":
            case "tidak ada":
            case "kosong":
                return "";
            default:
                return trimmed;
        }
    }

    /**
     * Cleans and normalizes phone number fields.
     * Extracts only the digits to check if it's a placeholder (like "+62", "(+62)",
     * "0", etc.).
     * Returns empty string if no valid subscriber digits are found.
     */
    private String cleanPhone(String value) {
        String normalized = normalizeField(value);
        if (normalized.isEmpty())
            return "";

        // Strip everything except digits
        String digits = normalized.replaceAll("[^0-9]", "");

        // If it contains no digits, or just country code/zero placeholders (e.g. "62",
        // "0")
        if (digits.isEmpty() || digits.equals("62") || digits.equals("0")) {
            return "";
        }
        return normalized;
    }
}
