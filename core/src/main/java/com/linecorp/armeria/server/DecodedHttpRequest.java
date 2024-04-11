/*
 * Copyright 2021 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server;

import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.internal.common.InboundTrafficController;
import com.linecorp.armeria.internal.server.DefaultServiceRequestContext;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;

interface DecodedHttpRequest extends HttpRequest {

    static DecodedHttpRequest of(boolean endOfStream, EventLoop eventLoop, int id, int streamId,
                                 RequestHeaders headers, boolean keepAlive,
                                 InboundTrafficController inboundTrafficController,
                                 RoutingContext routingCtx) {
        final long requestStartTimeNanos = System.nanoTime();
        final long requestStartTimeMicros = SystemInfo.currentTimeMicros();
        if (!routingCtx.hasResult()) {
            return new EmptyContentDecodedHttpRequest(
                    eventLoop, id, streamId, headers, keepAlive, routingCtx, ExchangeType.RESPONSE_STREAMING,
                    requestStartTimeNanos, requestStartTimeMicros);
        } else {
            final ServiceConfig config = routingCtx.result().value();
            final HttpService service = config.service();
            final ExchangeType exchangeType = service.exchangeType(routingCtx);
            if (endOfStream) {
                return new EmptyContentDecodedHttpRequest(
                        eventLoop, id, streamId, headers, keepAlive, routingCtx, exchangeType,
                        requestStartTimeNanos, requestStartTimeMicros);
            } else {
                if (exchangeType.isRequestStreaming()) {
                    return new StreamingDecodedHttpRequest(
                            eventLoop, id, streamId, headers, keepAlive, inboundTrafficController,
                            config.maxRequestLength(), routingCtx, exchangeType,
                            requestStartTimeNanos, requestStartTimeMicros, false, false);
                } else {
                    return new AggregatingDecodedHttpRequest(
                            eventLoop, id, streamId, headers, keepAlive, config.maxRequestLength(), routingCtx,
                            exchangeType, requestStartTimeNanos, requestStartTimeMicros);
                }
            }
        }
    }

    int id();

    int streamId();

    /**
     * Returns whether to keep the connection alive after this request is handled.
     */
    boolean isKeepAlive();

    void setServiceRequestContext(DefaultServiceRequestContext ctx);

    DefaultServiceRequestContext serviceRequestContext();

    void fireChannelRead(ChannelHandlerContext ctx);

    boolean isFired();

    RoutingContext routingContext();

    void close();

    void close(Throwable cause);

    /**
     * Sets the specified {@link HttpResponse} which responds to this request. This is always called
     * by the {@link HttpServerHandler} after the handler gets the {@link HttpResponse} from an
     * {@link HttpService}.
     */
    void setResponse(HttpResponse response);

    /**
     * Aborts the {@link HttpResponse} which responds to this request if it exists.
     *
     * @see Http2RequestDecoder#onRstStreamRead(ChannelHandlerContext, int, long)
     */
    void abortResponse(Throwable cause, boolean cancel);

    /**
     * Tells whether {@link #abortResponse(Throwable, boolean)} was called or not.
     */
    boolean isResponseAborted();

    /**
     * Returns whether the request should be fully aggregated before passed to the {@link HttpServerHandler}.
     */
    boolean needsAggregation();

    /**
     * Returns the {@link ExchangeType} that determines whether to stream an {@link HttpRequest} or
     * {@link HttpResponse}.
     */
    ExchangeType exchangeType();

    /**
     * Returns the {@link System#nanoTime()} value when the request started.
     */
    long requestStartTimeNanos();

    /**
     * Returns the number of microseconds since the epoch, e.g. {@code System.currentTimeMillis() * 1000},
     * when the request started.
     */
    long requestStartTimeMicros();

    /**
     * Returns whether the request is an HTTP/1.1 webSocket request.
     */
    default boolean isHttp1WebSocket() {
        return false;
    }

    /**
     * Returns a new {@link StreamingDecodedHttpRequest} whose corresponding response is aborted using the
     * specified {@link Throwable}. This is called when an {@link AggregatingDecodedHttpRequest}
     * needs to be passed to a service before it's closed.
     */
    default StreamingDecodedHttpRequest toAbortedStreaming(
            InboundTrafficController inboundTrafficController,
            Throwable cause, boolean shouldResetOnlyIfRemoteIsOpen) {
        throw new UnsupportedOperationException();
    }

    /**
     * Sets whether to send an RST_STREAM after the response sending response when the peer is open state.
     */
    default void setShouldResetOnlyIfRemoteIsOpen(boolean shouldResetOnlyIfRemoteIsOpen) {
        // no-op
    }

    /**
     * Tells whether to send an RST_STREAM after the response sending response when the peer is open state.
     */
    default boolean shouldResetOnlyIfRemoteIsOpen() {
        return false;
    }
}
