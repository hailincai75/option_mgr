package com.optionmgr.trade.domain;

/** FEAT-039: whether an STO call lot occupies shares (CHECKED) or was entered before the check (LEGACY, Q79). */
public enum CoverageStatus {
    CHECKED,
    LEGACY
}
