package com.bic.cloud.controlplane.controller;

import com.bic.cloud.controlplane.dto.CreateProjectDto;
import com.bic.cloud.controlplane.dto.CreateProjectImageDto;
import com.bic.cloud.controlplane.dto.CreateProjectResponse;
import com.bic.cloud.controlplane.dto.ProjectDetailResponse;
import com.bic.cloud.controlplane.dto.ProjectImageResponse;
import com.bic.cloud.controlplane.dto.ScaleRequest;
import com.bic.cloud.controlplane.dto.UpdateProjectImageDto;
import com.bic.cloud.controlplane.security.BicloudUserDetails;
import com.bic.cloud.controlplane.service.OrchestrationService;
import com.bic.cloud.controlplane.service.ProjectImageService;
import com.bic.cloud.controlplane.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/project")
public class ProjectController {

    private final ProjectService projectService;
    private final ProjectImageService projectImageService;
    private final OrchestrationService orchestrationService;

    @PostMapping("")
    public ResponseEntity<CreateProjectResponse> createProject(
            @Valid @RequestBody CreateProjectDto createProjectDto,
            @AuthenticationPrincipal BicloudUserDetails caller) {

        CreateProjectResponse response = projectService.createProject(createProjectDto, caller);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("")
    public ResponseEntity<List<ProjectDetailResponse>> listProjects(
            @AuthenticationPrincipal BicloudUserDetails caller) {
        return ResponseEntity.ok(projectService.listAllProjects(caller));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProjectDetailResponse> getProject(
            @PathVariable Long id,
            @AuthenticationPrincipal BicloudUserDetails caller) {
        return ResponseEntity.ok(projectService.getProjectDetail(id, caller));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProject(
            @PathVariable Long id,
            @AuthenticationPrincipal BicloudUserDetails caller) {
        orchestrationService.deleteProject(id, caller);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/image")
    public ResponseEntity<ProjectImageResponse> createProjectImage(
            @Valid @RequestBody CreateProjectImageDto createProjectImageDto,
            @AuthenticationPrincipal BicloudUserDetails caller) {

        ProjectImageResponse response = projectImageService.createProjectImage(createProjectImageDto, caller);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Updates the service configuration; running containers are recreated
     * with the new configuration. serviceName and the replica count cannot
     * be changed here (there is a separate scale endpoint).
     */
    @PutMapping("/image/{imageId}")
    public ResponseEntity<Void> updateProjectImage(
            @PathVariable Long imageId,
            @Valid @RequestBody UpdateProjectImageDto updateProjectImageDto,
            @AuthenticationPrincipal BicloudUserDetails caller) {
        orchestrationService.updateProjectImage(imageId, updateProjectImageDto, caller);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/image/{imageId}")
    public ResponseEntity<Void> deleteProjectImage(
            @PathVariable Long imageId,
            @AuthenticationPrincipal BicloudUserDetails caller) {
        orchestrationService.deleteProjectImage(imageId, caller);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/deploy")
    public ResponseEntity<Void> deploy(
            @PathVariable Long id,
            @AuthenticationPrincipal BicloudUserDetails caller) {
        orchestrationService.deployProject(id, caller);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/undeploy")
    public ResponseEntity<Void> undeploy(
            @PathVariable Long id,
            @AuthenticationPrincipal BicloudUserDetails caller) {
        orchestrationService.undeployProject(id, caller);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/image/{imageId}/scale")
    public ResponseEntity<Void> scale(
            @PathVariable Long imageId,
            @Valid @RequestBody ScaleRequest request,
            @AuthenticationPrincipal BicloudUserDetails caller) {

        orchestrationService.scale(imageId, request.getReplicas(), caller);
        return ResponseEntity.ok().build();
    }

    /**
     * Manually resets the self-healing backoff cooldown.
     * Called after a broken image/env has been fixed;
     * SelfHealingScheduler puts the service back into the retry loop.
     */
    @PostMapping("/image/{imageId}/reset-failures")
    public ResponseEntity<Void> resetDeployFailures(
            @PathVariable Long imageId,
            @AuthenticationPrincipal BicloudUserDetails caller) {

        projectImageService.resetDeployFailures(imageId, caller);
        return ResponseEntity.ok().build();
    }
}
