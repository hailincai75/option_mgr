package com.optionmgr.quote.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** FEAT-016: registers {@link QuoteProperties}. */
@Configuration
@EnableConfigurationProperties(QuoteProperties.class)
public class QuoteConfig {
}
