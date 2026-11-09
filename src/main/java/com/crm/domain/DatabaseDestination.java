package com.crm.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum DatabaseDestination {
    COMPANY, PARTICIPANT, BOTH;

    @JsonValue
    public String value() { return name().toLowerCase(Locale.ROOT); }

    @JsonCreator
    public static DatabaseDestination fromValue(String value) {
        if (value == null) return null;
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public static DatabaseDestination effective(DatabaseDestination value) {
        return value == null ? COMPANY : value;
    }

    public static boolean isExact(DatabaseDestination actual, DatabaseDestination selected) {
        return selected != null && selected != BOTH && effective(actual) == selected;
    }

    public boolean includesCompany() { return this == COMPANY; }
    public boolean includesParticipant() { return this == PARTICIPANT; }

    public boolean includes(DatabaseDestination destination) {
        return isExact(this, destination);
    }

    public DatabaseDestination add(DatabaseDestination other) {
        if (!isExact(this, effective(other))) throw new IllegalArgumentException("Database Perusahaan dan Peserta harus memakai profil terpisah.");
        return this;
    }
}
