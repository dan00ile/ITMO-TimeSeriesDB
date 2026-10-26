
package org.tsdb.server;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GrpcErrorsTest {

    @Test
    void illegalArgumentReturnsInvalidArgument() {
        IllegalArgumentException error =
                new IllegalArgumentException("end < start");

        StatusRuntimeException result = GrpcErrors.toStatus(error);

        assertEquals(
                Status.Code.INVALID_ARGUMENT,
                result.getStatus().getCode()
        );

        assertEquals(
                "end < start",
                result.getStatus().getDescription()
        );
    }

    @Test
    void illegalStateReturnsUnavailable() {
        IllegalStateException error =
                new IllegalStateException("Database is closed");

        StatusRuntimeException result = GrpcErrors.toStatus(error);

        assertEquals(
                Status.Code.UNAVAILABLE,
                result.getStatus().getCode()
        );

        assertEquals(
                "Database is closed",
                result.getStatus().getDescription()
        );
    }

    @Test
    void unexpectedErrorReturnsInternal() {
        RuntimeException error =
                new RuntimeException("Sensitive internal details");

        StatusRuntimeException result = GrpcErrors.toStatus(error);

        assertEquals(
                Status.Code.INTERNAL,
                result.getStatus().getCode()
        );

        assertEquals(
                "Internal server error",
                result.getStatus().getDescription()
        );
    }
}
