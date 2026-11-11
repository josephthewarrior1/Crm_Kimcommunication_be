package com.crm.repository;

import com.crm.domain.Database;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface DatabaseRepository extends JpaRepository<Database, Long> {
    @org.springframework.data.jpa.repository.Query(value = "SELECT pg_try_advisory_xact_lock(1129467209, :destinationKey)", nativeQuery = true)
    boolean tryLockImportDestination(@org.springframework.data.repository.query.Param("destinationKey") int destinationKey);

    @org.springframework.data.jpa.repository.Query("select distinct d from Database d left join fetch d.emails where d.databaseDestination = :destination order by d.id")
    List<Database> findForExcelImport(@org.springframework.data.repository.query.Param("destination") com.crm.domain.DatabaseDestination destination);

    boolean existsByBranchId(Long branchId);
    interface UploadCount {
        Long getUserId();
        String getMethod();
        long getTotal();
    }

    @org.springframework.data.jpa.repository.Query("""
            select d.createdByUserId as userId, d.entryMethod as method, count(d) as total
            from Database d
            where d.createdByUserId in :userIds and d.createdAt >= :start and d.createdAt < :end
              and d.entryMethod in ('manual', 'excel_import')
            group by d.createdByUserId, d.entryMethod
            """)
    List<UploadCount> countUploads(java.util.Collection<Long> userIds,
                                  java.time.LocalDateTime start, java.time.LocalDateTime end);

    List<Database> findByFirstNameIgnoreCaseAndLastNameIgnoreCase(String firstName, String lastName);
    List<Database> findByFirstNameIgnoreCase(String firstName);
    List<Database> findByMobilePhone(String mobilePhone);
    List<Database> findByNormalizedPhone(String normalizedPhone);
}
