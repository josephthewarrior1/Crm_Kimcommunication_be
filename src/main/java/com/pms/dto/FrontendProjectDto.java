package com.pms.dto;

import java.math.BigDecimal;

public record FrontendProjectDto(
        String id,
        String name,
        String client,
        Long clientId,
        String eventDate,
        String status,
        String statusLabel,
        Integer progress,
        Integer daysUntilEvent,
        String currentStage,
        String currentStageLabel,
        Integer target,
        String accountManager,
        String venueName,
        String venueCity,
        String remarks,
        BigDecimal hedging
) {}

