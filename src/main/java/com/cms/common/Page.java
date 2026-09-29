package com.cms.common;

import java.util.List;

/** One page of a list screen. {@code page} is zero-based. */
public record Page<T>(List<T> items, long total, int page, int size) {

    public static final int MAX_SIZE = 200;

    /** Clamp a requested page size to 1..MAX_SIZE. */
    public static int size(int requested) {
        return Math.max(1, Math.min(MAX_SIZE, requested));
    }

    public static int offset(int page, int size) {
        return Math.max(0, page) * size;
    }
}
