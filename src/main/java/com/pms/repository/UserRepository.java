package com.pms.repository;

import com.pms.domain.AppUser;
import com.pms.domain.EmploymentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.List;

public interface UserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByEmail(String email);

    Optional<AppUser> findByUsername(String username);

    Optional<AppUser> findByName(String name);

    Optional<AppUser> findByEmailOrUsername(String email, String username);

    boolean existsByEmail(String email);

    boolean existsByUsername(String username);

    @Query("SELECT u FROM AppUser u WHERE u.id NOT IN (SELECT tm.user.id FROM TeamMember tm)")
    List<AppUser> findUsersNotOnTeam();

    List<AppUser> findByEmploymentType(EmploymentType employmentType);

    List<AppUser> findByClientEntity_Id(Long clientId);
}
