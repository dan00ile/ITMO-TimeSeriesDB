package org.tsdb.block;

/**
 * Блок на диске повреждён.
 */
public class CorruptedBlockException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CorruptedBlockException(String message) {
        super(message);
    }

    public CorruptedBlockException(String message, Throwable cause) {
        super(message, cause);
    }
}