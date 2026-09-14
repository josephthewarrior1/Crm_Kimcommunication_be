package com.crm.repository;

import com.crm.domain.CompanyBranch;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CompanyBranchRepository extends JpaRepository<CompanyBranch, Long> {
    List<CompanyBranch> findByCompanyIdOrderByNameAsc(Long companyId);
    Optional<CompanyBranch> findByCompanyIdAndNameIgnoreCase(Long companyId, String name);
    boolean existsByCompanyId(Long companyId);
}
