package com.optionmgr.stock.web;

import com.optionmgr.stock.domain.StockTradeType;
import com.optionmgr.stock.service.StockTradeService;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * FEAT-011 create request: the time field required by the type - {@code tradedAtLocal} for BUY / SELL,
 * {@code tradeDate} for TRANSFER_IN - reported on that field together with the other field errors. A missing type is
 * left to {@code @NotNull}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = StockTradeTimeRequired.Validator.class)
public @interface StockTradeTimeRequired {

    String message() default StockTradeService.MISSING_TIME_MESSAGE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<StockTradeTimeRequired, StockTradeDtos.CreateStockTradeRequest> {
        @Override
        public boolean isValid(StockTradeDtos.CreateStockTradeRequest req, ConstraintValidatorContext context) {
            if (req == null || req.type() == null) {
                return true;
            }
            String field;
            String message;
            if (req.type() == StockTradeType.TRANSFER_IN) {
                if (req.tradeDate() != null) {
                    return true;
                }
                field = "tradeDate";
                message = StockTradeService.MISSING_DATE_MESSAGE;
            } else {
                if (req.tradedAtLocal() != null) {
                    return true;
                }
                field = "tradedAtLocal";
                message = StockTradeService.MISSING_TIME_MESSAGE;
            }
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(message).addPropertyNode(field).addConstraintViolation();
            return false;
        }
    }
}
