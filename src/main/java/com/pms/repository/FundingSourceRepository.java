package com.pms.repository;

import com.pms.domain.FundingSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FundingSourceRepository extends JpaRepository<FundingSource, Long> {
    List<FundingSource> findByAllianceId(Long allianceId);
    void deleteByAllianceId(Long allianceId);
}
