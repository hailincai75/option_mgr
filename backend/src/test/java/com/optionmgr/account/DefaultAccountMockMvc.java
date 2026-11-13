package com.optionmgr.account;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.optionmgr.account.web.AccountRef;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Test helper for the M1 controller tests: every MockMvc request carries {@code X-Account-Id: 1} (the default
 * account) unless the request sets the header itself. Import with {@code @Import(DefaultAccountMockMvc.class)}.
 */
@TestConfiguration
public class DefaultAccountMockMvc {

    @Bean
    MockMvcBuilderCustomizer defaultAccountHeader() {
        return builder -> builder.defaultRequest(get("/").header(AccountRef.HEADER, "1"));
    }
}
