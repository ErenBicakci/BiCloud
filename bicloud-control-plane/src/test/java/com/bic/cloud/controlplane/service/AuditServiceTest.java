package com.bic.cloud.controlplane.service;

import com.bic.cloud.controlplane.model.AuditEvent;
import com.bic.cloud.controlplane.model.BicloudUser;
import com.bic.cloud.controlplane.repository.AuditEventRepository;
import com.bic.cloud.controlplane.repository.BicloudUserRepository;
import com.bic.cloud.controlplane.security.BicloudUserDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuditServiceTest {

    @Autowired private AuditEventRepository auditEventRepository;
    @Autowired private BicloudUserRepository userRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUp() {
        auditEventRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("a failing audit insert does not roll back the audited operation")
    void failedAuditDoesNotRollBackCaller() {
        AuditService auditService = new AuditService(auditEventRepository, transactionManager);
        String longName = "u".repeat(120);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            BicloudUser user = userRepository.save(BicloudUser.builder()
                    .username(longName).password("x").role("USER").build());
            auditService.userAction(new BicloudUserDetails(user), AuditEvent.AuditAction.PROJECT_CREATED,
                    AuditEvent.TargetType.PROJECT, "demo", null, "Project created");
        });

        assertThat(userRepository.findByUsername(longName)).isPresent();
        assertThat(auditEventRepository.count()).isZero();
    }
}
