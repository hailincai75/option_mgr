package com.optionmgr.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.optionmgr.testsupport.service.DatabaseResetService;
import com.optionmgr.testsupport.web.TestSupportController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.MockMvc;

/** Without the e2e profile (normal "test" profile) the reset endpoint does not exist. */
@SpringBootTest
@AutoConfigureMockMvc
class TestSupportDisabledWithoutE2eProfileTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    Environment environment;

    @Test
    void e2eProfileIsNotActive() {
        assertThat(environment.getActiveProfiles()).doesNotContain(E2eProfile.NAME);
    }

    @Test
    void resetEndpointReturns404LikeAnyUnknownPath() throws Exception {
        mvc.perform(post("/api/test-support/reset"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/test-support/reset"));
    }

    @Test
    void testSupportBeansAreAbsent() {
        assertThat(context.getBeanNamesForType(TestSupportController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(DatabaseResetService.class)).isEmpty();
    }
}
