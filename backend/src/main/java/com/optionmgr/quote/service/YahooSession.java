package com.optionmgr.quote.service;

import org.springframework.stereotype.Service;

/**
 * FEAT-052: the Yahoo session crumb, kept in memory across runs (a restart handshakes again). The matching cookies
 * live in the transport ({@link HttpQuoteTransport}'s in-memory cookie store). Used inside the run lock; never
 * logged, stored or returned.
 */
@Service
public class YahooSession {

    private volatile String crumb;

    public String crumb() {
        return crumb;
    }

    public void set(String crumb) {
        this.crumb = crumb;
    }

    public void clear() {
        crumb = null;
    }
}
