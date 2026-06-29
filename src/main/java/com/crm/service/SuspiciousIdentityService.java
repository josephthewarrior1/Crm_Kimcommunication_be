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
    private ContactRepository contactRepository;

    @Autowired
    private ContactEmailRepository contactEmailRepository;

    public void checkAndFlagContact(Contact contact) {
        if (contact == null || contact.getId() == null) {
            return;
        }

        // 1. Auto-flag duplicate phone with different name
        String phone = cleanPhone(contact.getMobilePhone());
        if (!phone.isEmpty()) {
            String normalized = "+62" + phone.trim().replaceAll("^0", "");
            List<Contact> matchedContacts = contactRepository.findByNormalizedPhone(normalized);
            
            for (Contact other : matchedContacts) {
                if (other.getId().equals(contact.getId())) {
                    continue;
                }
                
                // If names are different
                if (!other.getFirstName().equalsIgnoreCase(contact.getFirstName()) || 
                    !other.getLastName().equalsIgnoreCase(contact.getLastName())) {
                    
                    // Flag the current contact
                    boolean contactAlreadyFlagged = flaggedIdentityRepository.findAll().stream()
                            .anyMatch(f -> f.getContact() != null && f.getContact().getId().equals(contact.getId()) 
                                           && f.getFlagReason() == FlagReason.duplicate_phone
                                           && f.getStatus() != FlagStatus.cleared);
                    
                    if (!contactAlreadyFlagged) {
                        FlaggedIdentity flag = FlaggedIdentity.builder()
                                .contact(contact)
                                .nameUsed(contact.getFirstName() + " " + contact.getLastName())
                                .phoneUsed(phone)
                                .flagReason(FlagReason.duplicate_phone)
                                .status(FlagStatus.suspected)
                                .evidenceNotes(String.format("Auto-flagged: Phone number %s matches contact %s %s (ID: %s)", 
                                        phone, other.getFirstName(), other.getLastName(), other.getId()))
                                .build();
                        flaggedIdentityRepository.save(flag);
                    }

                    // Flag the other contact as well
                    boolean otherAlreadyFlagged = flaggedIdentityRepository.findAll().stream()
                            .anyMatch(f -> f.getContact() != null && f.getContact().getId().equals(other.getId()) 
                                           && f.getFlagReason() == FlagReason.duplicate_phone
                                           && f.getStatus() != FlagStatus.cleared);
                    
                    if (!otherAlreadyFlagged) {
                        FlaggedIdentity flag = FlaggedIdentity.builder()
                                .contact(other)
                                .nameUsed(other.getFirstName() + " " + other.getLastName())
                                .phoneUsed(phone)
                                .flagReason(FlagReason.duplicate_phone)
                                .status(FlagStatus.suspected)
                                .evidenceNotes(String.format("Auto-flagged: Phone number %s matches contact %s %s (ID: %s)", 
                                        phone, contact.getFirstName(), contact.getLastName(), contact.getId()))
                                .build();
                        flaggedIdentityRepository.save(flag);
                    }
                }
            }
        }

        // 2. Auto-flag duplicate email with different name
        List<ContactEmail> contactEmails = contactEmailRepository.findAll().stream()
                .filter(e -> e.getContact() != null && e.getContact().getId().equals(contact.getId()))
                .toList();

        for (ContactEmail ce : contactEmails) {
            String emailStr = normalizeField(ce.getEmail());
            if (!emailStr.isEmpty()) {
                List<ContactEmail> matchedEmails = contactEmailRepository.findAll().stream()
                        .filter(e -> e.getEmail().equalsIgnoreCase(emailStr) && e.getContact() != null && !e.getContact().getId().equals(contact.getId()))
                        .toList();

                for (ContactEmail otherEmail : matchedEmails) {
                    Contact other = otherEmail.getContact();
                    
                    // If names are different
                    if (!other.getFirstName().equalsIgnoreCase(contact.getFirstName()) || 
                        !other.getLastName().equalsIgnoreCase(contact.getLastName())) {
                        
                        // Flag the current contact
                        boolean contactAlreadyFlagged = flaggedIdentityRepository.findAll().stream()
                                .anyMatch(f -> f.getContact() != null && f.getContact().getId().equals(contact.getId()) 
                                               && f.getFlagReason() == FlagReason.duplicate_email
                                               && f.getStatus() != FlagStatus.cleared);
                        
                        if (!contactAlreadyFlagged) {
                            FlaggedIdentity flag = FlaggedIdentity.builder()
                                    .contact(contact)
                                    .nameUsed(contact.getFirstName() + " " + contact.getLastName())
                                    .emailUsed(emailStr)
                                    .flagReason(FlagReason.duplicate_email)
                                    .status(FlagStatus.suspected)
                                    .evidenceNotes(String.format("Auto-flagged: Email %s matches contact %s %s (ID: %s)", 
                                            emailStr, other.getFirstName(), other.getLastName(), other.getId()))
                                    .build();
                            flaggedIdentityRepository.save(flag);
                        }

                        // Flag the other contact as well
                        boolean otherAlreadyFlagged = flaggedIdentityRepository.findAll().stream()
                                .anyMatch(f -> f.getContact() != null && f.getContact().getId().equals(other.getId()) 
                                               && f.getFlagReason() == FlagReason.duplicate_email
                                               && f.getStatus() != FlagStatus.cleared);
                        
                        if (!otherAlreadyFlagged) {
                            FlaggedIdentity flag = FlaggedIdentity.builder()
                                    .contact(other)
                                    .nameUsed(other.getFirstName() + " " + other.getLastName())
                                    .emailUsed(emailStr)
                                    .flagReason(FlagReason.duplicate_email)
                                    .status(FlagStatus.suspected)
                                    .evidenceNotes(String.format("Auto-flagged: Email %s matches contact %s %s (ID: %s)", 
                                            emailStr, contact.getFirstName(), contact.getLastName(), contact.getId()))
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
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return "";
        // Strings that are only dashes/hyphens (e.g. "-", "--", "---")
        if (trimmed.matches("^-+$")) return "";
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
     * Extracts only the digits to check if it's a placeholder (like "+62", "(+62)", "0", etc.).
     * Returns empty string if no valid subscriber digits are found.
     */
    private String cleanPhone(String value) {
        String normalized = normalizeField(value);
        if (normalized.isEmpty()) return "";
        
        // Strip everything except digits
        String digits = normalized.replaceAll("[^0-9]", "");
        
        // If it contains no digits, or just country code/zero placeholders (e.g. "62", "0")
        if (digits.isEmpty() || digits.equals("62") || digits.equals("0")) {
            return "";
        }
        return normalized;
    }
}
