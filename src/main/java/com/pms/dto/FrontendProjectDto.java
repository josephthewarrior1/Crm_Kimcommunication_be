package com.pms.dto;

public record FrontendProjectDto(
        String id,
        String name,
        String client,
        String eventDate,
        String status,
        String statusLabel,
        Integer progress,
        Integer daysUntilEvent,
        String currentStage,
        String currentStageLabel,
        String priority,
        String priorityLabel
) {}

