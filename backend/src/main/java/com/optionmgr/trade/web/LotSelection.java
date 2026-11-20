package com.optionmgr.trade.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;
import java.util.List;

/**
 * FEAT-051: field rules of {@code lotTradeIds} on a close / close edit request (class level, so that every error is
 * reported on its own field): non-empty (<code>请至少选择一笔开仓批次</code>), at most {@value #MAX_LOTS} ids
 * (<code>最多选择 1000 笔批次</code>), every id present and positive (<code>批次必须是开仓交易 id</code>). With
 * {@link #contractsRequiredWithoutLots()}, {@code contracts} is required when no lots are given (FIFO mode,
 * <code>张数不能为空</code>).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = LotSelection.Validator.class)
public @interface LotSelection {

    int MAX_LOTS = 1000;

    String message() default "所选批次无效";

    boolean contractsRequiredWithoutLots() default false;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** A request carrying a lot selection. */
    interface Request {
        BigDecimal contracts();

        List<Long> lotTradeIds();
    }

    class Validator implements ConstraintValidator<LotSelection, Request> {
        private boolean contractsRequired;

        @Override
        public void initialize(LotSelection a) {
            contractsRequired = a.contractsRequiredWithoutLots();
        }

        @Override
        public boolean isValid(Request r, ConstraintValidatorContext ctx) {
            if (r == null) {
                return true;
            }
            String lotsError = null;
            List<Long> lots = r.lotTradeIds();
            if (lots != null) {
                if (lots.isEmpty()) {
                    lotsError = "请至少选择一笔开仓批次";
                } else if (lots.size() > MAX_LOTS) {
                    lotsError = "最多选择 1000 笔批次";
                } else if (lots.stream().anyMatch(id -> id == null || id <= 0)) {
                    lotsError = "批次必须是开仓交易 id";
                }
            }
            String contractsError = contractsRequired && lots == null && r.contracts() == null ? "张数不能为空" : null;
            if (lotsError == null && contractsError == null) {
                return true;
            }
            ctx.disableDefaultConstraintViolation();
            if (lotsError != null) {
                ctx.buildConstraintViolationWithTemplate(lotsError).addPropertyNode("lotTradeIds")
                        .addConstraintViolation();
            }
            if (contractsError != null) {
                ctx.buildConstraintViolationWithTemplate(contractsError).addPropertyNode("contracts")
                        .addConstraintViolation();
            }
            return false;
        }
    }

    /** Elements must be JSON integers (a fraction or a string is a 400 BAD_REQUEST); null stays null. */
    class StrictLongDeserializer extends StdDeserializer<Long> {
        public StrictLongDeserializer() {
            super(Long.class);
        }

        @Override
        public Long deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.currentToken() == JsonToken.VALUE_NUMBER_INT) {
                return p.getLongValue();
            }
            return (Long) ctxt.handleUnexpectedToken(Long.class, p);
        }
    }
}
