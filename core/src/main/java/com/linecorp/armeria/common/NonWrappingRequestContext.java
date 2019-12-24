/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.common;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.linecorp.armeria.internal.ArmeriaHttpUtil;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.Channel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

/**
 * Default {@link RequestContext} implementation.
 */
public abstract class NonWrappingRequestContext extends AbstractRequestContext {

    private final MeterRegistry meterRegistry;
    private final DefaultAttributeMap attrs;
    private final SessionProtocol sessionProtocol;
    private final RequestId id;
    private final HttpMethod method;
    private final String path;
    @Nullable
    private String decodedPath;
    @Nullable
    private final String query;
    @Nullable
    private volatile HttpRequest req;
    @Nullable
    private volatile RpcRequest rpcReq;

    // Callbacks
    @Nullable
    private List<Consumer<? super RequestContext>> onEnterCallbacks;
    @Nullable
    private List<Consumer<? super RequestContext>> onExitCallbacks;
    @Nullable
    private List<BiConsumer<? super RequestContext, ? super RequestContext>> onChildCallbacks;

    /**
     * Creates a new instance.
     *
     * @param sessionProtocol the {@link SessionProtocol} of the invocation
     * @param id the {@link RequestId} associated with this context
     * @param req the {@link HttpRequest} associated with this context
     * @param rpcReq the {@link RpcRequest} associated with this context
     */
    protected NonWrappingRequestContext(
            MeterRegistry meterRegistry, SessionProtocol sessionProtocol,
            RequestId id, HttpMethod method, String path, @Nullable String query,
            @Nullable HttpRequest req, @Nullable RpcRequest rpcReq,
            @Nullable RequestContext rootAttributeMap) {

        this.meterRegistry = requireNonNull(meterRegistry, "meterRegistry");
        attrs = new DefaultAttributeMap(rootAttributeMap);
        this.sessionProtocol = requireNonNull(sessionProtocol, "sessionProtocol");
        this.id = requireNonNull(id, "id");
        this.method = requireNonNull(method, "method");
        this.path = requireNonNull(path, "path");
        this.query = query;
        this.req = req;
        this.rpcReq = rpcReq;
    }

    @Override
    public HttpRequest request() {
        return req;
    }

    @Override
    public RpcRequest rpcRequest() {
        return rpcReq;
    }

    @Override
    public final void updateRequest(HttpRequest req) {
        requireNonNull(req, "req");
        validateHeaders(req.headers());
        unsafeUpdateRequest(req);
    }

    @Override
    public final void updateRpcRequest(RpcRequest rpcReq) {
        requireNonNull(rpcReq, "rpcReq");
        this.rpcReq = rpcReq;
    }

    /**
     * Validates the specified {@link RequestHeaders}. By default, this method will raise
     * an {@link IllegalArgumentException} if it does not have {@code ":scheme"} or {@code ":authority"}
     * header.
     */
    protected void validateHeaders(RequestHeaders headers) {
        checkArgument(headers.scheme() != null && headers.authority() != null,
                      "must set ':scheme' and ':authority' headers");
    }

    /**
     * Replaces the {@link HttpRequest} associated with this context with the specified one
     * without any validation. Internal use only. Use it at your own risk.
     */
    protected final void unsafeUpdateRequest(HttpRequest req) {
        this.req = req;
    }

    @Override
    public final SessionProtocol sessionProtocol() {
        return sessionProtocol;
    }

    /**
     * Returns the {@link Channel} that is handling this request, or {@code null} if the connection is not
     * established yet.
     */
    @Nullable
    protected abstract Channel channel();

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <A extends SocketAddress> A remoteAddress() {
        final Channel ch = channel();
        return ch != null ? (A) ch.remoteAddress() : null;
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <A extends SocketAddress> A localAddress() {
        final Channel ch = channel();
        return ch != null ? (A) ch.localAddress() : null;
    }

    @Override
    public final RequestId id() {
        return id;
    }

    @Override
    public final HttpMethod method() {
        return method;
    }

    @Override
    public final String path() {
        return path;
    }

    @Override
    public final String decodedPath() {
        final String decodedPath = this.decodedPath;
        if (decodedPath != null) {
            return decodedPath;
        }

        return this.decodedPath = ArmeriaHttpUtil.decodePath(path);
    }

    @Override
    public final String query() {
        return query;
    }

    @Override
    public final MeterRegistry meterRegistry() {
        return meterRegistry;
    }

    @Nullable
    @Override
    public <V> V attr(AttributeKey<V> key) {
        requireNonNull(key, "key");
        return attrs.attr(key);
    }

    @Override
    public <V> void setAttr(AttributeKey<V> key, @Nullable V value) {
        requireNonNull(key, "key");
        requireNonNull(value, "value");
        attrs.setAttr(key, value);
    }

    @Nullable
    @Override
    public <V> V setAttrIfAbsent(AttributeKey<V> key, V value) {
        return attrs.setAttrIfAbsent(key, value);
    }

    @Override
    public Iterator<Map.Entry<AttributeKey<?>, Object>> attrs() {
        return attrs.attrs();
    }

    @Nullable
    public <V> V ownAttr(AttributeKey<V> key) {
        requireNonNull(key, "key");
        return attrs.ownAttr(key);
    }

    public Iterator<Map.Entry<AttributeKey<?>, Object>> ownAttrs() {
        return attrs.ownAttrs();
    }

//    @Override
//    public <T> Attribute<T> attr(AttributeKey<T> key) {
//        return attrs.attr(requireNonNull(key, "key"));
//    }
//
//    /**
//     * Returns the {@link Attribute} for the given {@link AttributeKey}. This method will never return
//     * {@code null}, but may return an {@link Attribute} which does not have a value set yet.
//     * Unlike {@link #attr(AttributeKey)}, this does not search in {@code rootMap}.
//     *
//     * @see #attr(AttributeKey)
//     */
//    public <T> Attribute<T> ownAttr(AttributeKey<T> key) {
//        return attrs.ownAttr(requireNonNull(key, "key"));
//    }
//
//    @Override
//    public <T> boolean hasAttr(AttributeKey<T> key) {
//        return attrs.hasAttr(requireNonNull(key, "key"));
//    }
//
//    /**
//     * Returns {@code} true if and only if the given {@link Attribute} exists.
//     * Unlike {@link #hasAttr(AttributeKey)}, this does not search in {@code rootMap}.
//     *
//     * @see #hasAttr(AttributeKey)
//     */
//    public <T> boolean hasOwnAttr(AttributeKey<T> key) {
//        return attrs.hasOwnAttr(requireNonNull(key, "key"));
//    }
//
//    @Override
//    public Iterator<Attribute<?>> attrs() {
//        return attrs.attrs();
//    }
//
//    /**
//     * Returns the {@link Iterator} of all {@link Attribute}s this map contains.
//     * Unlike {@link #attrs()}, this does not iterate {@code rootMap}.
//     *
//     * @see #attrs()
//     */
//    public Iterator<Attribute<?>> ownAttrs() {
//        return attrs.ownAttrs();
//    }

    @Override
    public final void onEnter(Consumer<? super RequestContext> callback) {
        requireNonNull(callback, "callback");
        if (onEnterCallbacks == null) {
            onEnterCallbacks = new ArrayList<>(4);
        }
        onEnterCallbacks.add(callback);
    }

    @Override
    public final void onExit(Consumer<? super RequestContext> callback) {
        requireNonNull(callback, "callback");
        if (onExitCallbacks == null) {
            onExitCallbacks = new ArrayList<>(4);
        }
        onExitCallbacks.add(callback);
    }

    @Override
    public final void onChild(BiConsumer<? super RequestContext, ? super RequestContext> callback) {
        requireNonNull(callback, "callback");
        if (onChildCallbacks == null) {
            onChildCallbacks = new ArrayList<>(4);
        }
        onChildCallbacks.add(callback);
    }

    @Override
    public void invokeOnEnterCallbacks() {
        invokeCallbacks(onEnterCallbacks);
    }

    @Override
    public void invokeOnExitCallbacks() {
        invokeCallbacks(onExitCallbacks);
    }

    private void invokeCallbacks(@Nullable List<Consumer<? super RequestContext>> callbacks) {
        if (callbacks == null) {
            return;
        }

        for (Consumer<? super RequestContext> callback : callbacks) {
            callback.accept(this);
        }
    }

    @Override
    public void invokeOnChildCallbacks(RequestContext newCtx) {
        final List<BiConsumer<? super RequestContext, ? super RequestContext>> callbacks = onChildCallbacks;
        if (callbacks == null) {
            return;
        }

        for (BiConsumer<? super RequestContext, ? super RequestContext> callback : callbacks) {
            callback.accept(this, newCtx);
        }
    }
}
