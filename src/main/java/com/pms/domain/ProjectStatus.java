package com.pms.domain;

public enum ProjectStatus {
    PENDING,
    PITCHING,
    APPROVED,
    COMPLETED,
    CANCELLED;

    public static ProjectStatus parse(String raw) {
        if (raw == null) {
            return null;
        }

        String normalized = raw.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "CANCEL" -> CANCELLED;
            case "IN_PROGRESS", "DELAYED" -> APPROVED;
            case "APPROVAL_PENDING" -> PENDING;
            case "DELIVERED" -> COMPLETED;
            default -> valueOf(normalized);
        };
    }
}

