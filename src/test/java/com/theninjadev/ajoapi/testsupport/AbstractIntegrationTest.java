package com.theninjadev.ajoapi.testsupport;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Every integration test shares this base: a real Postgres from Testcontainers and the "test"
 * profile (application-test.yml), which supplies a dummy JWT secret so no .env is needed.
 */
@ActiveProfiles("test")
@ExtendWith(ResetAdjustableClockExtension.class)
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    static {
        postgres.start();
    }
}
