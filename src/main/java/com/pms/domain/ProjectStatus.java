package com.pms.domain;

public enum ProjectStatus {
    PENDING,
    PITCHING,
    IN_PROGRESS,
    APPROVAL_PENDING,
    COMPLETED,
    DELIVERED,
    CANCELLED,
    DELAYED

    ;

    public static ProjectStatus parse(String raw) {
        if (raw == null) {
            return null;
        }

        String normalized = raw.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "CANCEL" -> CANCELLED;
            default -> valueOf(normalized);
        };
    }
}

