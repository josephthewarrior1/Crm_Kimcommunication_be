package com.pms.dto;

import java.time.LocalDateTime;

import com.pms.domain.EmploymentType;

public record ProjectMemberDto(
                Long id,
                UserSummaryDto user,
                RoleSummaryDto role,
                String jobTitle,
                LocalDateTime joinedAt,
                Long managerId,
                String managerName,
                String teamType,
                Long teamId,
                String teamName) {
        // Nested DTOs to match your Frontend structure
        public record UserSummaryDto(Long id, String name, String email, String phone, String location,
                        EmploymentType employmentType) {
        }

        public record RoleSummaryDto(
                Long id,
                String name,
                String description,
                boolean canCreate,
                boolean canRead,
                boolean canUpdate,
                boolean canDelete) {
        }
}