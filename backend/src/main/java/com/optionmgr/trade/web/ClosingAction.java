package com.optionmgr.trade.web;

import com.optionmgr.trade.domain.TradeAction;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The action must be a closing one (BTC / STC); null is left to {@code @NotNull}. */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ClosingAction.Validator.class)
public @interface ClosingAction {

    String message() default "本功能只支持平仓（BTC / STC），开仓请使用开仓功能";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ClosingAction, TradeAction> {
        @Override
        public boolean isValid(TradeAction value, ConstraintValidatorContext context) {
            return value == null || value.isUserClose();
        }
    }
}
