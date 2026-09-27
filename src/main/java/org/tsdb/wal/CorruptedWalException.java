package org.tsdb.wal;

/**
 * WAL повреждён и не может быть корректно прочитан.
 */
public class CorruptedWalException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CorruptedWalException(String message) {
        super(message);
    }

    public CorruptedWalException(String message, Throwable cause) {
        super(message, cause);
    }
}