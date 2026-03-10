package com.pms.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "funding_sources")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FundingSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "alliance_id", nullable = false)
    @JsonIgnore
    private ProjectBrandAlliance alliance;

    @NotNull
    @Column(nullable = false)
    private String source;

    @NotNull
    @DecimalMin("0.00")
    @Column(precision = 19, scale = 2, nullable = false)
    private BigDecimal amount;
}
