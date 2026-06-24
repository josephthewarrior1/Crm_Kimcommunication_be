package com.crm.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class PositionLevelConverter implements AttributeConverter<PositionLevel, String> {

    @Override
    public String convertToDatabaseColumn(PositionLevel attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.getValue();
    }

    @Override
    public PositionLevel convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        return PositionLevel.fromValue(dbData);
    }
}
