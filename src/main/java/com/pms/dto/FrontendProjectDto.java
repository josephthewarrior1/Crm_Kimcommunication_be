package com.pms.dto;

import java.math.BigDecimal;

public record FrontendProjectDto(
        String id,
        String name,
        String client,
        Long clientId,
        String startDate,
        String endDate,
        String eventDate,
        String status,
        String statusLabel,
        Integer progress,
        Integer daysUntilEvent,
        String currentStage,
        String currentStageLabel,
        Integer target,
        String accountManager,
        Long accountManagerUserId,
        String picName,
        Long picUserId,
        String venueName,
        String venueCity,
        Long venueId,
        Long venueRoomId,
        String venueRoomName,
        String venueAddress,
        String venueProvince,
        String venueGoogleMapsLink,
        String remarks,
        BigDecimal hedging,
        String qtnNo,
        String poNo,
        String invoiceNo
) {}

