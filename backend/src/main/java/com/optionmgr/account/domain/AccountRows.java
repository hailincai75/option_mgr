package com.optionmgr.account.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * FEAT-035 (Q214 / Q215, pure Java): rows of several accounts in one list. Each row is computed in its own account
 * (Q53); rows are never merged; the list keeps the endpoint's own order and breaks ties by account id ascending.
 */
public final class AccountRows {

    private AccountRows() {
    }

    /** One row and the account it belongs to. */
    public record Row<T>(long accountId, String accountName, T value) {
    }

    /** All rows sorted by {@code order}, equal rows by {@code accountId} ascending. */
    public static <T> List<Row<T>> sorted(Collection<Row<T>> rows, Comparator<? super T> order) {
        List<Row<T>> out = new ArrayList<>(rows);
        Comparator<Row<T>> byValue = (a, b) -> order.compare(a.value(), b.value());
        out.sort(byValue.thenComparingLong(Row::accountId));
        return List.copyOf(out);
    }

    /** FEAT-046: one page of a merged, already sorted list (1-based {@code page}; past the last page: no items). */
    public record Page<T>(List<T> items, long totalItems, int totalPages) {
        public Page {
            items = List.copyOf(items);
        }
    }

    /**
     * FEAT-046 rule 5: page {@code page} (1-based) of {@code size} rows of the whole merged list; totals are of the
     * whole list ({@code totalPages} 0 for an empty list).
     */
    public static <T> Page<T> page(List<T> sorted, int page, int size) {
        long total = sorted.size();
        int totalPages = (int) ((total + size - 1) / size);
        long from = (long) (page - 1) * size;
        List<T> items = from >= total ? List.of() : sorted.subList((int) from, (int) Math.min(from + size, total));
        return new Page<>(items, total, totalPages);
    }
}
