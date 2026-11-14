package com.optionmgr.quote.service;

import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * FEAT-016: serializes the short database writes of a run's apply step and of manual overrides, so an override
 * entered while a run is applying is neither lost nor half-applied (the network part of a run does not hold it).
 */
@Component
public class QuoteWriteLock {

    private final ReentrantLock lock = new ReentrantLock(true);

    public void lock() {
        lock.lock();
    }

    public void unlock() {
        lock.unlock();
    }
}
