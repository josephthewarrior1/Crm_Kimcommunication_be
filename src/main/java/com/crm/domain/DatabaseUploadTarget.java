package com.crm.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDate;

@Entity
@Table(name = "database_upload_targets", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "target_month"}))
@Getter
@Setter
@NoArgsConstructor
public class DatabaseUploadTarget {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "target_month", nullable = false)
    private LocalDate targetMonth;

    @Column(name = "target_count", nullable = false)
    private int targetCount;

    @Column(name = "target_mode", nullable = false)
    private String targetMode = "MONTHLY";
}
