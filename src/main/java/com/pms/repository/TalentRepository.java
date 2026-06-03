package com.pms.repository;

import com.pms.domain.Talent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TalentRepository extends JpaRepository<Talent, Long> {
    List<Talent> findAllByOrderByNameAsc();
}
