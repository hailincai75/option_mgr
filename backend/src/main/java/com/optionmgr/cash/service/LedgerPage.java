package com.optionmgr.cash.service;

import java.util.List;

/** One page of the ledger, newest first. {@code page} is 1-based. */
public record LedgerPage(List<LedgerLine> items, int page, int size, long totalItems, int totalPages) {
}
