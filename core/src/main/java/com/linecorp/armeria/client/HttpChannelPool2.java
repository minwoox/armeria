/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.client;

import java.lang.reflect.Array;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.jctools.queues.MpmcArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;

final class HttpChannelPool2 implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(HttpChannelPool2.class);

    private final EventLoop eventLoop;
    private boolean closed;

    // Fields for pooling connections:
    private final Map<PoolKey, Deque<PooledChannel>>[] pool;
    private final Map<PoolKey, CompletableFuture<PooledChannel>>[] pendingAcquisitions;
    private final Map<PoolKey, PendingAcquisition>[] pendingAcquisitions2;
    private final Map<Channel, Boolean> allChannels;
    private final ConnectionPoolListener listener;

    // Fields for creating a new connection:
    private final Bootstrap[] bootstraps;
    private final int connectTimeoutMillis;

    private final AddressResolverGroup<InetSocketAddress> addressResolverGroup;

    HttpChannelPool2(HttpClientFactory clientFactory, EventLoop eventLoop, ConnectionPoolListener listener,
                     AddressResolverGroup<InetSocketAddress> addressResolverGroup) {
        this.eventLoop = eventLoop;
        pool = newEnumMap(
                Map.class,
                unused -> new HashMap<>(),
                SessionProtocol.H1, SessionProtocol.H1C,
                SessionProtocol.H2, SessionProtocol.H2C);
        pendingAcquisitions = newEnumMap(
                Map.class,
                unused -> new HashMap<>(),
                SessionProtocol.HTTP, SessionProtocol.HTTPS,
                SessionProtocol.H1, SessionProtocol.H1C,
                SessionProtocol.H2, SessionProtocol.H2C);

        pendingAcquisitions2 = newEnumMap(
                Map.class,
                unused -> new HashMap<>(),
                SessionProtocol.HTTP, SessionProtocol.HTTPS,
                SessionProtocol.H2, SessionProtocol.H2C);

        allChannels = new IdentityHashMap<>();
        this.listener = listener;
        this.addressResolverGroup = addressResolverGroup;

        final Bootstrap baseBootstrap = clientFactory.newBootstrap();
        baseBootstrap.group(eventLoop);
        bootstraps = newEnumMap(
                Bootstrap.class,
                desiredProtocol -> {
                    final Bootstrap bootstrap = baseBootstrap.clone();
                    bootstrap.handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) throws Exception {
                            ch.pipeline().addLast(
                                    new HttpClientPipelineConfigurator(clientFactory, desiredProtocol));
                        }
                    });
                    return bootstrap;
                },
                SessionProtocol.HTTP, SessionProtocol.HTTPS,
                SessionProtocol.H1, SessionProtocol.H1C,
                SessionProtocol.H2, SessionProtocol.H2C);
        connectTimeoutMillis = (Integer) baseBootstrap.config().options()
                                                      .get(ChannelOption.CONNECT_TIMEOUT_MILLIS);
    }

    CompletableFuture<PooledChannel> pooledChannel(Endpoint endpoint, SessionProtocol sessionProtocol) {
        if (endpoint.hasIpAddr()) {
            final String ipAddr = endpoint.ipAddr();
            assert ipAddr != null;
            return pooledChannel(sessionProtocol, new PoolKey(ipAddr, endpoint.port()));
        } else {
            // IP address has not been resolved yet.
            final Future<InetSocketAddress> resolveFuture =
                    addressResolverGroup.getResolver(eventLoop)
                                        .resolve(InetSocketAddress.createUnresolved(endpoint.host(),
                                                                                    endpoint.port()));
            if (resolveFuture.isDone()) {
                return finishResolveAndGetPooledChannel(endpoint, sessionProtocol, resolveFuture);
            }

            final CompletableFuture<PooledChannel> result = new CompletableFuture<>();
            resolveFuture.addListener((FutureListener<InetSocketAddress>) resolved -> {
                finishResolveAndGetPooledChannel(endpoint, sessionProtocol, resolved)
                        .handle((pooledChannel, cause) -> {
                            if (cause != null) {
                                result.completeExceptionally(cause);
                            } else {
                                result.complete(pooledChannel);
                            }
                            return null;
                        });
            });
            return result;
        }
    }

    private CompletableFuture<PooledChannel> finishResolveAndGetPooledChannel(
            Endpoint endpoint, SessionProtocol sessionProtocol, Future<InetSocketAddress> resolveFuture) {
        if (resolveFuture.isSuccess()) {
            final PoolKey poolKey = new PoolKey(resolveFuture.getNow().getAddress().getHostAddress(),
                                                endpoint.port());
            return pooledChannel(sessionProtocol, poolKey);
        } else {
            return CompletableFutures.exceptionallyCompletedFuture(resolveFuture.cause());
        }
    }

    private CompletableFuture<PooledChannel> pooledChannel(SessionProtocol sessionProtocol, PoolKey poolKey) {
        final PooledChannel pooledChannel = acquireNow(sessionProtocol, poolKey);
        if (pooledChannel != null) {
            return CompletableFuture.completedFuture(pooledChannel);
        } else {
            return acquireLater(sessionProtocol, poolKey);
        }
    }

    private void acquire() {
        List<EventLoop> eventLoops = new ArrayList<>();

        final MpmcArrayQueue<Object> queue = new MpmcArrayQueue<>(100);
//        queue.relaxedOffer()
//
//        PooledChannel
//
//
//        if () {
//
//
//
//
//
//        }









    }


    /**
     * Returns an array whose index signifies {@link SessionProtocol#ordinal()}. Similar to {@link EnumMap}.
     */
    private static <T> T[] newEnumMap(Class<?> elementType,
                                      Function<SessionProtocol, T> factory,
                                      SessionProtocol... allowedProtocols) {
        @SuppressWarnings("unchecked")
        final T[] maps = (T[]) Array.newInstance(elementType, SessionProtocol.values().length);
        // Attempting to access the array with an unallowed protocol will trigger NPE,
        // which will help us find a bug.
        for (SessionProtocol p : allowedProtocols) {
            maps[p.ordinal()] = factory.apply(p);
        }
        return maps;
    }

    private Bootstrap getBootstrap(SessionProtocol desiredProtocol) {
        return bootstraps[desiredProtocol.ordinal()];
    }

    @Nullable
    private Deque<PooledChannel> getPool(SessionProtocol protocol, PoolKey key) {
        return pool[protocol.ordinal()].get(key);
    }

    private Deque<PooledChannel> getOrCreatePool(SessionProtocol protocol, PoolKey key) {
        return pool[protocol.ordinal()].computeIfAbsent(key, k -> new ArrayDeque<>());
    }

    @Nullable
    private CompletableFuture<PooledChannel> getPendingAcquisition(SessionProtocol desiredProtocol,
                                                                   PoolKey key) {
        return pendingAcquisitions[desiredProtocol.ordinal()].get(key);
    }

    private void setPendingAcquisition(SessionProtocol desiredProtocol, PoolKey key,
                                       CompletableFuture<PooledChannel> future) {
        pendingAcquisitions[desiredProtocol.ordinal()].put(key, future);

        final Map<PoolKey, CompletableFuture<PooledChannel>> pendingAcquisition =
                pendingAcquisitions[desiredProtocol.ordinal()];

        final PendingAcquisition pendingAcquisition1 =
                pendingAcquisitions2[desiredProtocol.ordinal()]
                        .computeIfAbsent(key, unused -> new PendingAcquisition());
    }

    private void removePendingAcquisition(SessionProtocol desiredProtocol, PoolKey key) {
        pendingAcquisitions[desiredProtocol.ordinal()].remove(key);
    }

    /**
     * Attempts to acquire a {@link Channel} which is matched by the specified condition immediately.
     *
     * @return {@code null} is there's no match left in the pool and thus a new connection has to be
     *         requested via {@link #acquireLater(SessionProtocol, PoolKey, ClientConnectionTimingsBuilder)}.
     */
    @Nullable
    PooledChannel acquireNow(SessionProtocol desiredProtocol, PoolKey key) {
        PooledChannel ch;
        switch (desiredProtocol) {
            case HTTP:
                ch = acquireNowExact(key, SessionProtocol.H2C);
                if (ch == null) {
                    ch = acquireNowExact(key, SessionProtocol.H1C);
                }
                break;
            case HTTPS:
                ch = acquireNowExact(key, SessionProtocol.H2);
                if (ch == null) {
                    ch = acquireNowExact(key, SessionProtocol.H1);
                }
                break;
            default:
                ch = acquireNowExact(key, desiredProtocol);
        }
        return ch;
    }

    @Nullable
    private PooledChannel acquireNowExact(PoolKey key, SessionProtocol protocol) {
        final Deque<PooledChannel> queue = getPool(protocol, key);
        if (queue == null) {
            return null;
        }

        synchronized (queue) {
            // Find the most recently released channel while cleaning up the unhealthy channels.
            for (int i = queue.size(); i > 0; i--) {
                final PooledChannel pooledChannel = queue.peekLast();
                if (!isHealthy(pooledChannel)) {
                    queue.removeLast();
                    continue;
                }

                final HttpSession session = HttpSession.get(pooledChannel.get());
                if (session.unfinishedResponses() >= session.maxUnfinishedResponses()) {
                    // The channel is full of streams so we cannot create a new one.
                    // Move the channel to the beginning of the queue so it has low priority.
                    queue.removeLast();
                    queue.addFirst(pooledChannel);
                    continue;
                }

                if (!protocol.isMultiplex()) {
                    queue.removeLast();
                }
                return pooledChannel;
            }
        }


        return null;
    }

    private static boolean isHealthy(PooledChannel pooledChannel) {
        final Channel ch = pooledChannel.get();
        return ch.isActive() && HttpSession.get(ch).canSendRequest();
    }

    @Nullable
    private static SessionProtocol getProtocolIfHealthy(Channel ch) {
        if (!ch.isActive()) {
            return null;
        }

        // Note that we do not need to check 'HttpSession.isActive()'
        // because an inactive session always returns null.
        return HttpSession.get(ch).protocol();
    }

    /**
     * Acquires a new {@link Channel} which is matched by the specified condition by making a connection
     * attempt or waiting for the current connection attempt in progress.
     */
    CompletableFuture<PooledChannel> acquireLater(SessionProtocol desiredProtocol, PoolKey key) {
        if (desiredProtocol == SessionProtocol.H1 || desiredProtocol == SessionProtocol.H1C) {
            // connect and return and put it back to the pool when it's done;
        } else {
            final PendingAcquisition pendingAcquisition = pendingAcquisitions2[desiredProtocol.ordinal()]
                    .computeIfAbsent(key, unused -> new PendingAcquisition());

            final CompletableFuture<PooledChannel> promise = new CompletableFuture<>();
            synchronized (pendingAcquisition) {
                final List<CompletableFuture<PooledChannel>> futures = pendingAcquisition.futures();
                if (!futures.isEmpty()) {
                    return futures.get(0);
                } else {
                    futures.add(promise);
                }
            }

            connect(desiredProtocol, key, promise);
        }


        final CompletableFuture<PooledChannel> promise = new CompletableFuture<>();
        if (!usePendingAcquisition(desiredProtocol, key, promise, timingsBuilder)) {
            connect(desiredProtocol, key, promise, timingsBuilder);
        }
        return promise;
    }

    CompletableFuture<PooledChannel> usePendingAcquisitionOrConnect(SessionProtocol desiredProtocol, PoolKey key) {
        final CompletableFuture<PooledChannel> result = new CompletableFuture<>();

        final PendingAcquisition pendingAcquisition = pendingAcquisitions2[desiredProtocol.ordinal()]
                .computeIfAbsent(key, unused -> new PendingAcquisition());

        final CompletableFuture<PooledChannel> pendingChannelFuture;
        final boolean needToConnect;
        synchronized (pendingAcquisition) {
            final List<CompletableFuture<PooledChannel>> futures = pendingAcquisition.futures();
            if (!futures.isEmpty()) {
                needToConnect = false;
                pendingChannelFuture = futures.get(0); // Use index
            } else {
                needToConnect = true;
                pendingChannelFuture = new CompletableFuture<>();
                futures.add(pendingChannelFuture);
            }
        }

        if (needToConnect) {
            connect(desiredProtocol, key, pendingChannelFuture);
        }

        pendingChannelFuture.handle((pch, cause) -> {
            if (cause == null) {
                final SessionProtocol actualProtocol = pch.protocol();
                if (actualProtocol.isMultiplex()) {
                    result.complete(pch);
                } else {
                    // Try to acquire again because the connection was not HTTP/2.
                    // We use the exact protocol (H1 or H1C) instead of 'desiredProtocol' so that
                    // we do not waste our time looking for pending acquisitions for the host
                    // that does not support HTTP/2.
                    final PooledChannel ch = acquireNow(actualProtocol, key);
                    if (ch != null) {
                        result.complete(ch);
                    } else {
                        connect(actualProtocol, key, result);
                    }
                }
            } else {
                // The pending connection attempt has failed.
                connect(desiredProtocol, key, result);
            }
            return null;
        });

        return result;
    }




    /**
     * Tries to use the pending HTTP/2 connection to avoid creating an extra connection.
     *
     * @return {@code true} if succeeded to reuse the pending connection.
     */
    private boolean usePendingAcquisition(SessionProtocol desiredProtocol, PoolKey key,
                                          CompletableFuture<PooledChannel> promise,
                                          ClientConnectionTimingsBuilder timingsBuilder) {

        if (desiredProtocol == SessionProtocol.H1 || desiredProtocol == SessionProtocol.H1C) {
            // Can't use HTTP/1 connections because they will not be available in the pool until
            // the request is done.
            return false;
        }

        final PendingAcquisition pendingAcquisition = pendingAcquisitions2[desiredProtocol.ordinal()]
                .computeIfAbsent(key, unused -> new PendingAcquisition());

        synchronized (pendingAcquisition) {
            final List<CompletableFuture<PooledChannel>> futures = pendingAcquisition.futures();
            if (!futures.isEmpty()) {
                return futures.get(0);
            } else {
                futures.add(promise);
            }
        }

        connect(desiredProtocol, key, promise);

        pendingAcquisition.handle((pch, cause) -> {
            timingsBuilder.pendingAcquisitionEnd();

            if (cause == null) {
                final SessionProtocol actualProtocol = pch.protocol();
                if (actualProtocol.isMultiplex()) {
                    promise.complete(pch);
                } else {
                    // Try to acquire again because the connection was not HTTP/2.
                    // We use the exact protocol (H1 or H1C) instead of 'desiredProtocol' so that
                    // we do not waste our time looking for pending acquisitions for the host
                    // that does not support HTTP/2.
                    final PooledChannel ch = acquireNow(actualProtocol, key);
                    if (ch != null) {
                        promise.complete(ch);
                    } else {
                        connect(actualProtocol, key, promise);
                    }
                }
            } else {
                // The pending connection attempt has failed.
                connect(desiredProtocol, key, promise);
            }
            return null;
        });

        return true;
    }

    private void connect(SessionProtocol desiredProtocol, PoolKey key, CompletableFuture<PooledChannel> promise) {
        // Need to bring one event loop.

        final InetSocketAddress remoteAddress;
        try {
            remoteAddress = toRemoteAddress(key);
        } catch (UnknownHostException e) {
            notifyConnect(desiredProtocol, key, eventLoop.newFailedFuture(e), promise);
            return;
        }

        // Fail immediately if it is sure that the remote address doesn't support the desired protocol.
        if (SessionProtocolNegotiationCache.isUnsupported(remoteAddress, desiredProtocol)) {
            notifyConnect(desiredProtocol, key,
                          eventLoop.newFailedFuture(
                                  new SessionProtocolNegotiationException(
                                          desiredProtocol, "previously failed negotiation")),
                          promise);
            return;
        }

        // Create a new connection.
        final Promise<Channel> sessionPromise = eventLoop.newPromise();
        connect(remoteAddress, desiredProtocol, sessionPromise);

        if (sessionPromise.isDone()) {
            notifyConnect(desiredProtocol, key, sessionPromise, promise);
        } else {
            sessionPromise.addListener(
                    (Future<Channel> future) -> notifyConnect(desiredProtocol, key, future, promise));
        }
    }

    /**
     * A low-level operation that triggers a new connection attempt. Used only by:
     * <ul>
     *   <li>{@link #connect(SessionProtocol, PoolKey, CompletableFuture, ClientConnectionTimingsBuilder)} -
     *       The pool has been exhausted.</li>
     *   <li>{@link HttpSessionHandler} - HTTP/2 upgrade has failed.</li>
     * </ul>
     */
    void connect(SocketAddress remoteAddress, SessionProtocol desiredProtocol,
                 Promise<Channel> sessionPromise) {
        final Bootstrap bootstrap = getBootstrap(desiredProtocol);
        final ChannelFuture connectFuture = bootstrap.connect(remoteAddress);

        connectFuture.addListener((ChannelFuture future) -> {
            if (future.isSuccess()) {
                initSession(desiredProtocol, future, sessionPromise);
            } else {
                sessionPromise.setFailure(future.cause());
            }
        });
    }

    private static InetSocketAddress toRemoteAddress(PoolKey key) throws UnknownHostException {
        final InetAddress inetAddr = InetAddress.getByAddress(
                key.host, NetUtil.createByteArrayFromIpAddressString(key.ipAddr));
        return new InetSocketAddress(inetAddr, key.port);
    }

    private void initSession(SessionProtocol desiredProtocol, ChannelFuture connectFuture,
                             Promise<Channel> sessionPromise) {
        assert connectFuture.isSuccess();

        final Channel ch = connectFuture.channel();
        final EventLoop eventLoop = ch.eventLoop();
        assert eventLoop.inEventLoop();

        final ScheduledFuture<?> timeoutFuture = eventLoop.schedule(() -> {
            if (sessionPromise.tryFailure(new SessionProtocolNegotiationException(
                    desiredProtocol, "connection established, but session creation timed out: " + ch))) {
                ch.close();
            }
        }, connectTimeoutMillis, TimeUnit.MILLISECONDS);

        ch.pipeline().addLast(new HttpSessionHandler(null, ch, sessionPromise, timeoutFuture));
    }

    private void notifyConnect(SessionProtocol desiredProtocol, PoolKey key, Future<Channel> future,
                               CompletableFuture<PooledChannel> promise) {
        assert future.isDone();
        removePendingAcquisition(desiredProtocol, key);
        try {
            if (future.isSuccess()) {
                final Channel channel = future.getNow();
                final SessionProtocol protocol = getProtocolIfHealthy(channel);
                if (closed || protocol == null) {
                    channel.close();
                    promise.completeExceptionally(
                            new UnprocessedRequestException(ClosedSessionException.get()));
                    return;
                }

                allChannels.put(channel, Boolean.TRUE);

                try {
                    listener.connectionOpen(protocol,
                                            (InetSocketAddress) channel.remoteAddress(),
                                            (InetSocketAddress) channel.localAddress(),
                                            channel);
                } catch (Exception e) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("{} Exception handling {}.connectionOpen()",
                                    channel, listener.getClass().getName(), e);
                    }
                }

                final HttpSession session = HttpSession.get(channel);
                if (session.unfinishedResponses() < session.maxUnfinishedResponses()) {
                    if (protocol.isMultiplex()) {
                        final Http2PooledChannel pooledChannel = new Http2PooledChannel(channel, protocol);
                        addToPool(protocol, key, pooledChannel);
                        promise.complete(pooledChannel);
                    } else {
                        promise.complete(new Http1PooledChannel(channel, protocol, key));
                    }
                } else {
                    // Server set MAX_CONCURRENT_STREAMS to 0, which means we can't send anything.
                    channel.close();
                    promise.completeExceptionally(
                            new UnprocessedRequestException(RefusedStreamException.get()));
                }

                channel.closeFuture().addListener(f -> {
                    allChannels.remove(channel);

                    // Clean up old unhealthy channels by iterating from the beginning of the queue.
                    final Deque<PooledChannel> queue = getPool(protocol, key);
                    if (queue != null) {
                        for (; ; ) {
                            final PooledChannel pooledChannel = queue.peekFirst();
                            if (pooledChannel == null || isHealthy(pooledChannel)) {
                                break;
                            }
                            queue.removeFirst();
                        }
                    }

                    try {
                        listener.connectionClosed(protocol,
                                                  (InetSocketAddress) channel.remoteAddress(),
                                                  (InetSocketAddress) channel.localAddress(),
                                                  channel);
                    } catch (Exception e) {
                        if (logger.isWarnEnabled()) {
                            logger.warn("{} Exception handling {}.connectionClosed()",
                                        channel, listener.getClass().getName(), e);
                        }
                    }
                });
            } else {
                promise.completeExceptionally(new UnprocessedRequestException(future.cause()));
            }
        } catch (Exception e) {
            promise.completeExceptionally(new UnprocessedRequestException(e));
        }
    }

    /**
     * Adds a {@link Channel} to this pool.
     */
    private void addToPool(SessionProtocol actualProtocol, PoolKey key, PooledChannel pooledChannel) {
        assert eventLoop.inEventLoop() : Thread.currentThread().getName();
        getOrCreatePool(actualProtocol, key).addLast(pooledChannel);
    }

    /**
     * Closes all {@link Channel}s managed by this pool.
     */
    @Override
    public void close() {
        closed = true;

        if (eventLoop.inEventLoop()) {
            // While we'd prefer to block until the pool is actually closed, we cannot block for the channels to
            // close if it was called from the event loop or we would deadlock. In practice, it's rare to call
            // close from an event loop thread, and not a main thread.
            doCloseAsync();
        } else {
            doCloseSync();
        }
    }

    private void doCloseAsync() {
        if (allChannels.isEmpty()) {
            return;
        }

        final List<ChannelFuture> closeFutures = new ArrayList<>(allChannels.size());
        for (Channel ch : allChannels.keySet()) {
            // NB: Do not call close() here, because it will trigger the closeFuture listener
            //     which mutates allChannels.
            closeFutures.add(ch.closeFuture());
        }

        closeFutures.forEach(f -> f.channel().close());
    }

    private void doCloseSync() {
        final CountDownLatch outerLatch = eventLoop.submit(() -> {
            if (allChannels.isEmpty()) {
                return null;
            }

            final int numChannels = allChannels.size();
            final CountDownLatch latch = new CountDownLatch(numChannels);
            final List<ChannelFuture> closeFutures = new ArrayList<>(numChannels);
            for (Channel ch : allChannels.keySet()) {
                // NB: Do not call close() here, because it will trigger the closeFuture listener
                //     which mutates allChannels.
                final ChannelFuture f = ch.closeFuture();
                closeFutures.add(f);
                f.addListener((ChannelFutureListener) future -> latch.countDown());
            }
            closeFutures.forEach(f -> f.channel().close());
            return latch;
        }).syncUninterruptibly().getNow();

        if (outerLatch != null) {
            boolean interrupted = false;
            while (outerLatch.getCount() != 0) {
                try {
                    outerLatch.await();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static final class PoolKey {
        final String ipAddr;
        final int port;
        final int hashCode;

        PoolKey(String ipAddr, int port) {
            this.ipAddr = ipAddr;
            this.port = port;
            hashCode = ipAddr.hashCode() * 31 + port;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }

            if (!(o instanceof PoolKey)) {
                return false;
            }

            final PoolKey that = (PoolKey) o;
            // Compare IP address first, which is most likely to differ.
            return ipAddr.equals(that.ipAddr) &&
                   port == that.port;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("ipAddr", ipAddr)
                              .add("port", port)
                              .toString();
        }
    }

    private static final class State {

        private final Object lock = new Object();

        private final Endpoint endpoint = Endpoint.of("a");

        private final AddressResolverGroup<InetSocketAddress> addressResolverGroup;

        private final EventLoop resolverExecutor = null;

        State(AddressResolverGroup<InetSocketAddress> addressResolverGroup) {
            this.addressResolverGroup = addressResolverGroup;
        }

        CompletableFuture<PooledChannel> acquire(SessionProtocol protocol) {
            if (endpoint.hasIpAddr()) {
                final String ipAddr = endpoint.ipAddr();
                assert ipAddr != null;
                return pooledChannel(protocol, new PoolKey(ipAddr, endpoint.port()));
            } else {
                // IP address has not been resolved yet.
                final Future<InetSocketAddress> resolveFuture =
                        addressResolverGroup.getResolver(resolverExecutor)
                                            .resolve(InetSocketAddress.createUnresolved(endpoint.host(),
                                                                                        endpoint.port()));
                if (resolveFuture.isDone()) {
                    return finishResolveAndGetPooledChannel(endpoint, protocol, resolveFuture);
                }

                final CompletableFuture<PooledChannel> result = new CompletableFuture<>();
                resolveFuture.addListener((FutureListener<InetSocketAddress>) resolved -> {
                    finishResolveAndGetPooledChannel(endpoint, protocol, resolved)
                            .handle((pooledChannel, cause) -> {
                                if (cause != null) {
                                    result.completeExceptionally(cause);
                                } else {
                                    result.complete(pooledChannel);
                                }
                                return null;
                            });
                });
                return result;
            }
        }

        private CompletableFuture<PooledChannel> pooledChannel(SessionProtocol protocol, PoolKey poolKey) {
            final PooledChannel pooledChannel = acquireNow(protocol, poolKey);
            if (pooledChannel != null) {
                return CompletableFuture.completedFuture(pooledChannel);
            } else {
                return acquireLater(protocol, poolKey);
            }
        }

        private CompletableFuture<PooledChannel> finishResolveAndGetPooledChannel(
                Endpoint endpoint, SessionProtocol sessionProtocol, Future<InetSocketAddress> resolveFuture) {
            if (resolveFuture.isSuccess()) {
                final PoolKey poolKey = new PoolKey(resolveFuture.getNow().getAddress().getHostAddress(),
                                                    endpoint.port());
                return pooledChannel(sessionProtocol, poolKey);
            } else {
                return CompletableFutures.exceptionallyCompletedFuture(resolveFuture.cause());
            }
        }
    }


    static final class Http2PooledChannel extends PooledChannel {
        Http2PooledChannel(Channel channel, SessionProtocol protocol) {
            super(channel, protocol);
        }

        @Override
        public void release() {
            // There's nothing to do here because we keep the connection in the pool after acquisition.
        }
    }

    final class Http1PooledChannel extends PooledChannel {
        private final PoolKey key;

        Http1PooledChannel(Channel channel, SessionProtocol protocol, PoolKey key) {
            super(channel, protocol);
            this.key = key;
        }

        @Override
        public void release() {
            if (!eventLoop.inEventLoop()) {
                eventLoop.execute(this::doRelease);
            } else {
                doRelease();
            }
        }

        private void doRelease() {
            if (isHealthy(this)) {
                // Channel turns out to be healthy. Add it back to the pool.
                addToPool(protocol(), key, this);
            } else {
                // Channel not healthy. Do not add it back to the pool.
            }
        }
    }

    private static final class PendingAcquisition {
        private final List<CompletableFuture<PooledChannel>> futures = new ArrayList<>();

        List<CompletableFuture<PooledChannel>> futures() {
            return futures;
        }

    }
}
