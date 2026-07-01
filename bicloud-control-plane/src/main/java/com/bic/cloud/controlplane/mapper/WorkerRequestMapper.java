package com.bic.cloud.controlplane.mapper;

import com.bic.cloud.controlplane.dto.CreateProjectImageDto;
import com.bic.cloud.controlplane.dto.WorkerContainerCreateRequest;
import com.bic.cloud.controlplane.model.ProjectImage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WorkerRequestMapper {

    public WorkerContainerCreateRequest toWorkerRequest(ProjectImage projectImage){
        WorkerContainerCreateRequest workerContainerCreateRequest = new WorkerContainerCreateRequest();
        workerContainerCreateRequest.setImageName(projectImage.getImageName());
        workerContainerCreateRequest.setContainerPort(projectImage.getContainerPort());
        workerContainerCreateRequest.setServiceName(projectImage.getServiceName());
        workerContainerCreateRequest.setProjectName(projectImage.getProject().getName());
        if (projectImage.getCpuLimit() != null) {
            workerContainerCreateRequest.setCpuLimitMillicores(
                    (int) (projectImage.getCpuLimit() * 1000)
            );
        }
        workerContainerCreateRequest.setEnv(projectImage.getEnvironmentVariables());
        workerContainerCreateRequest.setMemoryLimitMb(projectImage.getMemoryLimitMb());
        return workerContainerCreateRequest;
    }
}
