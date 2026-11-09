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

    public boolean includesCompany() { return this != PARTICIPANT; }
    public boolean includesParticipant() { return this != COMPANY; }

    public boolean includes(DatabaseDestination destination) {
        return destination == COMPANY ? includesCompany() : destination == PARTICIPANT && includesParticipant();
    }

    public DatabaseDestination add(DatabaseDestination other) {
        return this == effective(other) ? this : BOTH;
    }
}
