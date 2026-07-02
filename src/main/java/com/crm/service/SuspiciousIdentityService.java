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
        String phone = cleanPhone(database.getMobilePhone());
        if (!phone.isEmpty()) {
            String normalized = "+62" + phone.trim().replaceAll("^0", "");
            List<Database> matchedDatabases = databaseRepository.findByNormalizedPhone(normalized);

            for (Database other : matchedDatabases) {
                if (other.getId().equals(database.getId())) {
                    continue;
                }

                // If names are different
                if (!other.getFirstName().equalsIgnoreCase(database.getFirstName()) ||
                        !other.getLastName().equalsIgnoreCase(database.getLastName())) {

                    // Flag the current database record
                    boolean databaseAlreadyFlagged = flaggedIdentityRepository.findAll().stream()
                            .anyMatch(f -> f.getDatabase() != null && f.getDatabase().getId().equals(database.getId())
                                    && f.getFlagReason() == FlagReason.duplicate_phone
                                    && f.getStatus() != FlagStatus.cleared);

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
                    boolean otherAlreadyFlagged = flaggedIdentityRepository.findAll().stream()
                            .anyMatch(f -> f.getDatabase() != null && f.getDatabase().getId().equals(other.getId())
                                    && f.getFlagReason() == FlagReason.duplicate_phone
                                    && f.getStatus() != FlagStatus.cleared);

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

        // 2. Auto-flag duplicate email with different name
        List<DatabaseEmail> databaseEmails = databaseEmailRepository.findAll().stream()
                .filter(e -> e.getDatabase() != null && e.getDatabase().getId().equals(database.getId()))
                .toList();

        for (DatabaseEmail ce : databaseEmails) {
            String emailStr = normalizeField(ce.getEmail());
            if (!emailStr.isEmpty()) {
                List<DatabaseEmail> matchedEmails = databaseEmailRepository.findAll().stream()
                        .filter(e -> e.getEmail().equalsIgnoreCase(emailStr) && e.getDatabase() != null
                                && !e.getDatabase().getId().equals(database.getId()))
                        .toList();

                for (DatabaseEmail otherEmail : matchedEmails) {
                    Database other = otherEmail.getDatabase();

                    // If names are different
                    if (!other.getFirstName().equalsIgnoreCase(database.getFirstName()) ||
                            !other.getLastName().equalsIgnoreCase(database.getLastName())) {

                        // Flag the current database record
                        boolean databaseAlreadyFlagged = flaggedIdentityRepository.findAll().stream()
                                .anyMatch(f -> f.getDatabase() != null && f.getDatabase().getId().equals(database.getId())
                                        && f.getFlagReason() == FlagReason.duplicate_email
                                        && f.getStatus() != FlagStatus.cleared);

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
                        boolean otherAlreadyFlagged = flaggedIdentityRepository.findAll().stream()
                                .anyMatch(f -> f.getDatabase() != null && f.getDatabase().getId().equals(other.getId())
                                        && f.getFlagReason() == FlagReason.duplicate_email
                                        && f.getStatus() != FlagStatus.cleared);

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
            }
        }
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
