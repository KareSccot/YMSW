package com.wuxibio.care.service;

import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class TimeDependentService {

    public boolean isEffective(LocalDate startDate, LocalDate endDate, LocalDate asOfDate) {
        LocalDate point = asOfDate == null ? LocalDate.now() : asOfDate;
        LocalDate start = startDate == null ? LocalDate.of(1970, 1, 1) : startDate;
        LocalDate end = endDate == null ? LocalDate.of(9999, 12, 31) : endDate;
        return !point.isBefore(start) && !point.isAfter(end);
    }

    public LocalDate normalizeStart(LocalDate startDate) {
        return startDate == null ? LocalDate.of(1970, 1, 1) : startDate;
    }

    public LocalDate normalizeEnd(LocalDate endDate) {
        return endDate == null ? LocalDate.of(9999, 12, 31) : endDate;
    }
}
