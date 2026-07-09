package com.bic.cloud.controlplane.scheduler;

import com.bic.cloud.controlplane.service.AutoscalingService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AutoscalingScheduler {

    private final AutoscalingService autoscalingService;

    @Scheduled(
            fixedDelayString = "${bicloud.autoscaling.interval-ms:30000}",
            initialDelayString = "${bicloud.autoscaling.initial-delay-ms:90000}"
    )
    public void reconcile() {
        autoscalingService.reconcileAll();
    }
}
