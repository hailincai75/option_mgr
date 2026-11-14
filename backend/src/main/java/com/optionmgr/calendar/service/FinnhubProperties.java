package com.optionmgr.calendar.service;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * FEAT-009 settings ({@code optionmgr.finnhub.*}).
 *
 * @param baseUrl         Finnhub base URL; tests point it at a fake server
 * @param apiKey          test seam: a key that overrides FINNHUB_API_KEY (environment and .env); never set it in
 *                        application.yml with a real key
 * @param envFiles        candidate .env files, first existing one is read (relative to the working directory: the
 *                        project root when run from backend/ is {@code ../.env})
 * @param readSystemEnv   whether the FINNHUB_API_KEY environment variable is consulted (off in tests)
 * @param fetchEnabled    fetch at startup and daily at 08:00 New York (off in the test and e2e profiles; a test may
 *                        opt in, or call HolidayFetchService.fetchNow() directly)
 * @param connectTimeoutSeconds connect timeout
 * @param readTimeoutSeconds    response timeout
 */
@ConfigurationProperties(prefix = "optionmgr.finnhub")
public record FinnhubProperties(String baseUrl, String apiKey, List<String> envFiles, Boolean readSystemEnv, Boolean fetchEnabled,
                                Integer connectTimeoutSeconds, Integer readTimeoutSeconds) {

    public FinnhubProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://finnhub.io" : baseUrl.strip();
        envFiles = envFiles == null ? List.of(".env", "../.env") : List.copyOf(envFiles);
        readSystemEnv = readSystemEnv == null || readSystemEnv;
        fetchEnabled = fetchEnabled == null || fetchEnabled;
        connectTimeoutSeconds = connectTimeoutSeconds == null ? 5 : connectTimeoutSeconds;
        readTimeoutSeconds = readTimeoutSeconds == null ? 10 : readTimeoutSeconds;
    }
}
