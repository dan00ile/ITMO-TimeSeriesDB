package org.tsdb.block;

import java.io.Serial;

/**
 * Блок на диске повреждён.
 */
public class CorruptedBlockException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public CorruptedBlockException(String message) {
        super(message);
    }

    public CorruptedBlockException(String message, Throwable cause) {
        super(message, cause);
    }
}