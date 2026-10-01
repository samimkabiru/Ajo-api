package com.theninjadev.ajoapi.testsupport;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Resets every AdjustableClock in the test's Spring context before each test. Test classes that
 * import AdjustableClockConfig share a cached context, and with it one clock, so without this a
 * jump made by one class carries into the next. Registered on AbstractIntegrationTest, so a new
 * test class gets it without having to remember a @BeforeEach.
 */
public class ResetAdjustableClockExtension implements BeforeEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        SpringExtension.getApplicationContext(context)
                .getBeansOfType(AdjustableClock.class)
                .values()
                .forEach(AdjustableClock::reset);
    }
}
