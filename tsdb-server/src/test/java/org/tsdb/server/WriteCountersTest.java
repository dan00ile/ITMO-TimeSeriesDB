
package org.tsdb.server;

import org.junit.jupiter.api.Test;
import org.tsdb.proto.WriteResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WriteCountersTest {

    @Test
    void countsAcceptedAndRejected() {
        WriteCounters counters = new WriteCounters();

        counters.accepted();
        counters.accepted();
        counters.accepted();

        counters.rejected("Out of order");
        counters.rejected("Out of order");

        WriteResponse response = counters.toResponse();

        assertEquals(3, response.getAccepted());
        assertEquals(2, response.getRejected());
        assertEquals(2, response.getErrorsCount());
    }

    @Test
    void storesOnlyFirstTenErrors() {
        WriteCounters counters = new WriteCounters();

        for (int i = 0; i < 15; i++) {
            counters.rejected("Error " + i);
        }

        WriteResponse response = counters.toResponse();

        assertEquals(0, response.getAccepted());
        assertEquals(15, response.getRejected());
        assertEquals(10, response.getErrorsCount());

        assertEquals("Error 0", response.getErrors(0));
        assertEquals("Error 9", response.getErrors(9));
    }

    @Test
    void emptyCountersReturnZeros() {
        WriteCounters counters = new WriteCounters();

        WriteResponse response = counters.toResponse();

        assertEquals(0, response.getAccepted());
        assertEquals(0, response.getRejected());
        assertEquals(0, response.getErrorsCount());
    }
}
