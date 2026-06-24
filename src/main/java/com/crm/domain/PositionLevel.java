package com.crm.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum PositionLevel {
    C_LEVEL_GM_DIRECTOR("C-level//GM/Director"),
    MANAGERIAL_HEAD("Manajerial/Head"),
    STAFF("Staff"),
    UNKNOWN("unknown");

    private final String value;

    PositionLevel(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static PositionLevel fromValue(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        // Handle double/single slashes or case insensitivity
        String norm = value.trim().replace("/", "");
        for (PositionLevel pl : PositionLevel.values()) {
            String plNorm = pl.value.replace("/", "");
            if (plNorm.equalsIgnoreCase(norm) || pl.name().replace("_", "").equalsIgnoreCase(norm)) {
                return pl;
            }
        }
        // Generic fallback mapping
        if (value.toLowerCase().contains("manager") || value.toLowerCase().contains("head")) {
            return MANAGERIAL_HEAD;
        }
        if (value.toLowerCase().contains("director") || value.toLowerCase().contains("general manager") || value.toLowerCase().contains("gm") || value.toLowerCase().contains("c-level")) {
            return C_LEVEL_GM_DIRECTOR;
        }
        if (value.toLowerCase().contains("staff") || value.toLowerCase().contains("engineer") || value.toLowerCase().contains("admin")) {
            return STAFF;
        }
        return UNKNOWN;
    }
}
