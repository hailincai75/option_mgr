package com.optionmgr.testsupport.web;

import com.optionmgr.testsupport.E2eProfile;
import com.optionmgr.testsupport.service.DatabaseResetService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints for the Playwright suite. The bean exists only with the {@code e2e} profile; in normal
 * runs the paths are unknown and return 404 like any other unknown path.
 */
@RestController
@Profile(E2eProfile.NAME)
@RequestMapping("/api/test-support")
public class TestSupportController {

    private final DatabaseResetService resetService;

    public TestSupportController(DatabaseResetService resetService) {
        this.resetService = resetService;
    }

    /** Deletes all business data and restores the freshly migrated state (ids restart). */
    @PostMapping("/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset() {
        resetService.reset();
    }
}
