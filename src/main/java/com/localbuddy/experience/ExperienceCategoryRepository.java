package com.localbuddy.experience;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExperienceCategoryRepository extends JpaRepository<ExperienceCategory, UUID> {

    Optional<ExperienceCategory> findBySlug(String slug);

    boolean existsBySlug(String slug);

    boolean existsByNameIgnoreCase(String name);

    List<ExperienceCategory> findByActiveTrueOrderByDisplayOrderAscNameAsc();

    List<ExperienceCategory> findAllByOrderByDisplayOrderAscNameAsc();
}