package com.optionmgr.calendar.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** FEAT-009: registers {@link FinnhubProperties}. */
@Configuration
@EnableConfigurationProperties(FinnhubProperties.class)
public class CalendarConfig {
}
