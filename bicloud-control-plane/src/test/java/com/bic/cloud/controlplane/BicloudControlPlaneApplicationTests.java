package com.bic.cloud.controlplane;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:bicloud-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"bicloud.api-key=test-api-key",
		"bicloud.gateway.api-key=test-gateway-key",
		"jwt.secret=test-jwt-secret-with-at-least-thirty-two-chars",
		"jwt.expiration=86400000"
})
class BicloudControlPlaneApplicationTests {

	@Test
	void contextLoads() {
	}

}
