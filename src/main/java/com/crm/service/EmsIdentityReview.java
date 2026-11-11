package com.crm.service;

import com.crm.domain.DatabaseDestination;
import com.crm.domain.EventParticipant;
import com.crm.repository.EventParticipantRepository;
import com.crm.util.ContactPhone;
import com.crm.util.EventApprovalPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Refresh derived alias metadata after a CRM Client Approval decision changes. */
public final class EmsIdentityReview {
    private EmsIdentityReview() {}

    public static void refresh(EventParticipantRepository repository, Long eventId) {
        if (eventId == null) return;
        List<EventParticipant> participants = repository.findByEventId(eventId).stream()
                .filter(participant -> participant.getDatabase() != null
                        && DatabaseDestination.effective(participant.getDatabase().getDatabaseDestination()) == DatabaseDestination.COMPANY)
                .toList();
        List<EventParticipant> changed = new ArrayList<>();
        // ponytail: pairwise checks stay event-scoped; index phone owners if large events make refresh slow.
        for (EventParticipant participant : participants) {
            String original = participant.getRawEmsIdentityReviewMessage();
            if (!EventApprovalPolicy.isEmsManaged(participant.getEvent())
                    || participant.getEvent().getSyncDestination() != DatabaseDestination.COMPANY
                    || participant.getEmsApprovalStatus() == null || original == null || original.isBlank()) continue;
            Set<String> phones = phones(participant);
            List<EventParticipant> aliases = participants.stream()
                    .filter(other -> !Objects.equals(other.getDatabase().getId(), participant.getDatabase().getId())
                            && !Collections.disjoint(phones, phones(other))).toList();
            boolean rawDecline = aliases.stream()
                    .anyMatch(alias -> "decline".equals(EventApprovalPolicy.normalizeApproval(alias.getConfirmationStatus())));
            String message;
            if (!aliases.isEmpty()) {
                message = "HP pada profil yang dipilih juga ditemukan pada profil lain: "
                        + aliases.stream().map(alias -> (Objects.toString(alias.getDatabase().getFirstName(), "") + " "
                                + Objects.toString(alias.getDatabase().getLastName(), "")).trim())
                            .distinct().sorted().collect(java.util.stream.Collectors.joining(", ")) + ". Profil tidak digabung.";
            } else if (Boolean.TRUE.equals(participant.getRawEmsClientDeclineReviewRequired())) {
                message = null;
            } else {
                message = original;
            }
            if (!Objects.equals(original, message) || !Objects.equals(participant.getRawEmsClientDeclineReviewRequired(), rawDecline)) {
                participant.setEmsIdentityReviewMessage(message);
                participant.setEmsClientDeclineReviewRequired(rawDecline);
                changed.add(participant);
            }
        }
        if (!changed.isEmpty()) repository.saveAll(changed);
    }

    private static Set<String> phones(EventParticipant participant) {
        Set<String> phones = ContactPhone.normalizedNumbers(participant.getDatabase().getMobilePhone());
        phones.addAll(ContactPhone.normalizedNumbers(participant.getDatabase().getNormalizedPhone()));
        return phones;
    }
}
