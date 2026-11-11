package com.crm.util;

import com.crm.domain.Event;
import java.util.Locale;

public final class EventApprovalPolicy {
    private EventApprovalPolicy() {}

    public static boolean isEmsManaged(Event event) {
        return event != null && event.getEmsEventId() != null && event.getEmsEventId() > 0;
    }

    public static boolean isClientApproved(String approval) {
        return approval != null && java.util.Set.of("approve", "confirmed").contains(approval.trim().toLowerCase(Locale.ROOT));
    }

    public static String identityReviewMessage(String message, Boolean declinedAlias, String clientApproval) {
        return Boolean.TRUE.equals(declinedAlias) && isClientApproved(clientApproval)
                ? "Client sudah meng-Approve profil ini. Profil terkait tetap terpisah dan keputusan client mereka dipertahankan."
                : message;
    }

    public static String normalizeApproval(String value) {
        if (value == null) return "pending";
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "approve", "approved", "accepted" -> "approve";
            case "decline", "declined" -> "decline";
            default -> "pending";
        };
    }
}
