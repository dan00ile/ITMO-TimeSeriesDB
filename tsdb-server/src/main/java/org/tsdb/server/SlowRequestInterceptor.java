
package org.tsdb.server;

import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

public final class SlowRequestInterceptor implements ServerInterceptor {

    private static final Logger LOG =
            LoggerFactory.getLogger(SlowRequestInterceptor.class);

    private static final long SLOW_THRESHOLD_NANOS =
            TimeUnit.SECONDS.toNanos(1);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        long startTime = System.nanoTime();

        ServerCall<ReqT, RespT> wrappedCall =
                new ForwardingServerCall.SimpleForwardingServerCall<>(call) {

                    @Override
                    public void close(Status status, Metadata trailers) {
                        long elapsed = System.nanoTime() - startTime;

                        if (elapsed > SLOW_THRESHOLD_NANOS) {
                            LOG.warn(
                                    "Slow gRPC request: method={}, durationMs={}, status={}",
                                    call.getMethodDescriptor().getFullMethodName(),
                                    TimeUnit.NANOSECONDS.toMillis(elapsed),
                                    status.getCode()
                            );
                        }

                        super.close(status, trailers);
                    }
                };

        return next.startCall(wrappedCall, headers);
    }
}
