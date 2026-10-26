
package org.tsdb.server;

import org.tsdb.proto.WriteResponse;

import java.util.ArrayList;
import java.util.List;

final class WriteCounters {

    private static final int MAX_ERRORS = 10;

    private long accepted;
    private long rejected;

    private final List<String> errors = new ArrayList<>();

    void accepted() {
        accepted++;
    }

    void rejected(String message) {
        rejected++;

        if (errors.size() < MAX_ERRORS) {
            errors.add(message);
        }
    }

    WriteResponse toResponse() {
        return WriteResponse.newBuilder()
                .setAccepted(accepted)
                .setRejected(rejected)
                .addAllErrors(errors)
                .build();
    }
}
