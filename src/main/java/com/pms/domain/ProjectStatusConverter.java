package com.pms.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class ProjectStatusConverter implements AttributeConverter<ProjectStatus, String> {

    @Override
    public String convertToDatabaseColumn(ProjectStatus attribute) {
        return attribute != null ? attribute.name() : null;
    }

    @Override
    public ProjectStatus convertToEntityAttribute(String dbData) {
        return ProjectStatus.parse(dbData);
    }
}
