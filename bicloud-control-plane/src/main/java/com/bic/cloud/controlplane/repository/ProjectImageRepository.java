package com.bic.cloud.controlplane.repository;

import com.bic.cloud.controlplane.model.ProjectImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectImageRepository extends JpaRepository<ProjectImage, Long> {
    Optional<ProjectImage> findById(Long id);

    @Query("""
        SELECT pi
        FROM ProjectImage pi
        JOIN FETCH pi.project
        WHERE pi.id = :id
    """)
    Optional<ProjectImage> findByIdWithProject(Long id);

    List<ProjectImage> findByProject_Id(Long projectId);

    @Query("""
        SELECT pi FROM ProjectImage pi
        JOIN FETCH pi.project
    """)
    List<ProjectImage> findAllWithProject();


}
