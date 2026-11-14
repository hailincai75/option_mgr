package com.optionmgr.trade.web;

import com.optionmgr.common.error.ApiException;
import com.optionmgr.common.error.ErrorCode;
import com.optionmgr.common.error.ErrorResponse.FieldError;
import com.optionmgr.trade.domain.ContractKey;
import com.optionmgr.trade.web.OptionTradeDtos.BatchExpireRequest;
import com.optionmgr.trade.web.OptionTradeDtos.ExpireRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * FEAT-015 request validation: the item list (1..{@value #MAX_ITEMS}), every item with the FEAT-012 rules (field
 * names {@code items[i].field}) and no contract twice (each repeat reported as {@code items[i]}). All problems are
 * reported together as one 400 VALIDATION_ERROR, sorted by field like the common handler.
 */
final class BatchExpireValidation {

    static final int MAX_ITEMS = 500;
    static final String VALIDATION_FAILED = "Request validation failed";

    private BatchExpireValidation() {
    }

    /** @return the contracts of the items, in request order; throws ApiException(VALIDATION_ERROR) on any problem */
    static List<ContractKey> validate(Validator validator, BatchExpireRequest req) {
        List<ExpireRequest> items = req == null ? null : req.items();
        if (items == null || items.isEmpty()) {
            throw invalid(List.of(new FieldError("items", "请至少选择一个持仓")));
        }
        if (items.size() > MAX_ITEMS) {
            throw invalid(List.of(new FieldError("items", "一次最多作废 " + MAX_ITEMS + " 个持仓")));
        }
        Set<FieldError> errors = new LinkedHashSet<>();
        List<ContractKey> contracts = new ArrayList<>();
        Set<ContractKey> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            ExpireRequest item = items.get(i);
            String prefix = "items[" + i + "]";
            if (item == null) {
                errors.add(new FieldError(prefix, "持仓不能为空"));
                contracts.add(null);
                continue;
            }
            Set<ConstraintViolation<ExpireRequest>> violations = validator.validate(item);
            for (ConstraintViolation<ExpireRequest> v : violations) {
                errors.add(new FieldError(prefix + "." + v.getPropertyPath(), v.getMessage()));
            }
            if (!violations.isEmpty()) {
                contracts.add(null);
                continue;
            }
            ContractKey key = new ContractKey(item.symbol(), item.expirationDate(), item.strike(), item.optionType());
            if (!seen.add(key)) {
                errors.add(new FieldError(prefix, "同一合约（" + key.label() + "）不能出现两次"));
            }
            contracts.add(key);
        }
        if (!errors.isEmpty()) {
            throw invalid(new ArrayList<>(errors));
        }
        return contracts;
    }

    private static ApiException invalid(List<FieldError> errors) {
        List<FieldError> sorted = errors.stream()
                .sorted(Comparator.comparing(FieldError::field).thenComparing(FieldError::message))
                .toList();
        return new ApiException(ErrorCode.VALIDATION_ERROR, VALIDATION_FAILED, sorted);
    }
}
