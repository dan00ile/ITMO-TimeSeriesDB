package org.tsdb.server;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GrpcErrors {

    private static final Logger LOG =
            LoggerFactory.getLogger(GrpcErrors.class);

    private GrpcErrors() {
    }

    public static StatusRuntimeException toStatus(Throwable error) {
        if (error instanceof IllegalArgumentException) {
            return Status.INVALID_ARGUMENT
                    .withDescription(error.getMessage())
                    .asRuntimeException();
        }

        if (error instanceof IllegalStateException) {
            return Status.UNAVAILABLE
                    .withDescription(error.getMessage())
                    .asRuntimeException();
        }

        LOG.error("Unexpected server error", error);

        return Status.INTERNAL
                .withDescription("Internal server error")
                .asRuntimeException();
    }
}