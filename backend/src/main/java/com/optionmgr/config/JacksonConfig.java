package com.optionmgr.config;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.core.StreamWriteFeature;
import java.math.BigDecimal;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JSON conventions (see docs/api/api-contract.md "通用约定"):
 * <ul>
 *   <li>Every {@link BigDecimal} is written as a JSON string in plain notation, e.g. {@code "349.35"},
 *       never as a JSON number and never in scientific notation.</li>
 *   <li>Incoming amounts may be sent as strings (preferred) or numbers; both bind to BigDecimal.</li>
 *   <li>Dates/instants are ISO-8601 strings (configured in application.yml).</li>
 * </ul>
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer bigDecimalAsPlainString() {
        return builder -> builder.postConfigurer(mapper -> {
            mapper.configOverride(BigDecimal.class)
                    .setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.STRING));
            mapper.getFactory().enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN.mappedFeature());
        });
    }
}
