package com.crm.repository;

import com.crm.domain.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CompanyRepository extends JpaRepository<Company, Long> {
    interface IndustrySummary {
        String getIndustry();
        long getCompanyCount();
        long getDatabaseCount();
        long getActiveDatabaseCount();
        long getInactiveDatabaseCount();
    }

    // Aggregate separately so a company is counted once, regardless of its contacts/emails.
    @org.springframework.data.jpa.repository.Query(value = """
            select industry,
                   sum(company_count) as "companyCount",
                   sum(database_count) as "databaseCount",
                   sum(active_count) as "activeDatabaseCount",
                   sum(inactive_count) as "inactiveDatabaseCount"
            from (
                select coalesce(nullif(trim(industry), ''), 'Unspecified') as industry,
                       count(*) as company_count, 0 as database_count, 0 as active_count, 0 as inactive_count
                from companies
                group by coalesce(nullif(trim(industry), ''), 'Unspecified')
                union all
                select coalesce(nullif(trim(c.industry), ''), 'Unspecified') as industry,
                       0 as company_count, count(*) as database_count,
                       sum(case when d.is_active = false then 0 else 1 end) as active_count,
                       sum(case when d.is_active = false then 1 else 0 end) as inactive_count
                from databases d left join companies c on c.id = d.company_id
                group by coalesce(nullif(trim(c.industry), ''), 'Unspecified')
            ) counts
            group by industry
            order by sum(database_count) desc, sum(company_count) desc, industry asc
            """, nativeQuery = true)
    java.util.List<IndustrySummary> summarizeIndustries();

    Optional<Company> findByNameIgnoreCase(String name);
}
