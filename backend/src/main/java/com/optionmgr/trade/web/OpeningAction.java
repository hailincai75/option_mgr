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

/** The action must be an opening one (BTO / STO); null is left to {@code @NotNull}. */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = OpeningAction.Validator.class)
public @interface OpeningAction {

    String message() default "本功能只支持开仓（BTO / STO），平仓请使用平仓功能";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<OpeningAction, TradeAction> {
        @Override
        public boolean isValid(TradeAction value, ConstraintValidatorContext context) {
            return value == null || value.isOpening();
        }
    }
}
