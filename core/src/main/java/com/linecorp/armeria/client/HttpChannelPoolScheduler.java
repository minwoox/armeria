package com.linecorp.armeria.client;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;

final class HttpChannelPoolScheduler {

    private static final Logger logger = LoggerFactory.getLogger(HttpChannelPoolScheduler.class);

    private final Map<PoolKey, CompletableFuture<Pool>> pools = new ConcurrentHashMap<>();

    private final Map<Integer, EventLoop> resolverEventLoops = new ConcurrentHashMap<>();

    private final List<EventLoop> eventLoops;

    private final HttpClientFactory clientFactory;
    private final ConnectionPoolListener listener;
    private final AddressResolverGroup<InetSocketAddress> addressResolverGroup;
    private final int connectTimeoutMillis;

    HttpChannelPoolScheduler(HttpClientFactory clientFactory, EventLoopGroup eventLoopGroup,
                             ConnectionPoolListener listener,
                             AddressResolverGroup<InetSocketAddress> addressResolverGroup) {
        this.clientFactory = clientFactory;
        eventLoops = Streams.stream(eventLoopGroup)
                            .map(EventLoop.class::cast)
                            .collect(toImmutableList());
        this.listener = listener;
        this.addressResolverGroup = addressResolverGroup;
//        pool = newEnumMap(
//                Map.class,
//                unused -> new HashMap<>(),
//                SessionProtocol.H1, SessionProtocol.H1C,
//                SessionProtocol.H2, SessionProtocol.H2C);

        final Bootstrap baseBootstrap = clientFactory.newBootstrap();
        baseBootstrap.group(eventLoopGroup);
        connectTimeoutMillis = (Integer) baseBootstrap.config().options()
                                                      .get(ChannelOption.CONNECT_TIMEOUT_MILLIS);
    }

    CompletableFuture<PooledChannel> acquire(ClientRequestContext ctx, HttpRequest req,
                                             Endpoint endpoint, SessionProtocol protocol) {
        requireNonNull(ctx, "ctx");
        requireNonNull(req, "req");
        requireNonNull(endpoint, "endpoint");
        requireNonNull(protocol, "protocol");

        final CompletableFuture<PooledChannel> result = new CompletableFuture<>();
        final CompletableFuture<Pool> future = pool(ctx, req, endpoint, protocol);
        future.handle((pool, cause) -> {
            if (cause != null) {
                result.completeExceptionally(cause);
                return null;
            }
            final CompletableFuture<PooledChannel> pooledChannelFuture = pool.pooledChannel();
            pooledChannelFuture.handle((pooledChannel, cause2) -> {
                if (cause2 != null) {
                    result.completeExceptionally(cause2);
                } else {
                    result.complete(pooledChannel);
                }
                return null;
            });
            return null;
        });
        return result;
    }

    private CompletableFuture<Pool> pool(ClientRequestContext ctx, HttpRequest req, Endpoint endpoint,
                                         SessionProtocol protocol) {
        final String host = extractHost(req, endpoint);
        if (endpoint.hasIpAddr()) {
            final String ipAddr = endpoint.ipAddr();
            assert ipAddr != null;
            return pool(new PoolKey(protocol, host, ipAddr, endpoint.port()));
        } else {
            // IP address has not been resolved yet.
            final Future<InetSocketAddress> resolveFuture =
                    addressResolverGroup.getResolver(resolverEventLoop(endpoint))
                                        .resolve(InetSocketAddress.createUnresolved(endpoint.host(),
                                                                                    endpoint.port()));
            final CompletableFuture<Pool> result = new CompletableFuture<>();
            if (resolveFuture.isDone()) {
                finishResolve(host, endpoint, protocol, resolveFuture, result);
            } else {
                resolveFuture.addListener(
                        (FutureListener<InetSocketAddress>) future ->
                                finishResolve(host, endpoint, protocol, future, result));
            }
            return result;
        }
    }

    private CompletableFuture<Pool> pool(PoolKey poolKey) {
        final CompletableFuture<Pool> future = pools.get(poolKey);
        if (future != null) {
            return future;
        }

        final CompletableFuture<Pool> candidate = new CompletableFuture<>();
        final CompletableFuture<Pool> result = pools.computeIfAbsent(poolKey, unused -> candidate);
        if (result != candidate) {
            // Created by the another thread.
            return result;
        }

        final SessionProtocol protocol = poolKey.protocol;
        if (protocol.isMultiplex()) {
            result.complete(new Http2ChannelPool());
        } else if (protocol == SessionProtocol.H1 || protocol == SessionProtocol.H1C) {
            result.complete(new Http1ChannelPool());
        } else {
            final CompletableFuture<PooledChannel> promise = new CompletableFuture<>();
            connect(poolKey, promise);
            promise.handle((pooledChannel, cause) -> {
                if (cause != null) {
                    result.completeExceptionally(cause);
                    return null;
                }

                final Pool pool;
                final SessionProtocol resolvedProtocol = pooledChannel.protocol();
                if (resolvedProtocol.isMultiplex()) {
                    pool = new Http2ChannelPool();
                } else {
                    pool = new Http1ChannelPool();
                }
                pool.add(pooledChannel);
                result.complete(pool);

                final PoolKey newPoolKey = new PoolKey(resolvedProtocol, poolKey.host,
                                                       poolKey.ipAddr, poolKey.port);
                pools.putIfAbsent(newPoolKey, result);
                return null;
            });
        }
        return result;
    }

    private interface Pool {
        void add(PooledChannel channel);

        CompletableFuture<PooledChannel> pooledChannel();
    }

    private static class Http1ChannelPool implements Pool {
        private final List<EventLoop> eventLoops;
        private final Deque<PooledChannel> pool = new ArrayDeque<>();

        Http1ChannelPool() {
            eventLoops = null;
        }

        Http1ChannelPool(List<EventLoop> eventLoops) {
            this.eventLoops = eventLoops;
        }

        @Override
        public void add(PooledChannel channel) {
            pool.add(channel);
        }

        @Override
        public CompletableFuture<PooledChannel> pooledChannel() {



            return null;
        }
    }

    private static class Http2ChannelPool implements Pool {
        private final List<EventLoop> eventLoops;

        Http2ChannelPool() {
            eventLoops = null;
        }

        Http2ChannelPool(List<EventLoop> eventLoops) {
            this.eventLoops = eventLoops;
        }

        @Override
        public void add(PooledChannel channel) {

        }

        @Override
        public CompletableFuture<PooledChannel> pooledChannel() {
            return null;
        }
    }

    void connect(PoolKey poolKey, CompletableFuture<PooledChannel> promise) {
        final InetSocketAddress remoteAddress;
        try {
            remoteAddress = toRemoteAddress(poolKey);
        } catch (UnknownHostException e) {
            promise.completeExceptionally(e);
            return;
        }

        final SessionProtocol protocol = poolKey.protocol;
        // Fail immediately if it is sure that the remote address doesn't support the desired protocol.
        if (SessionProtocolNegotiationCache.isUnsupported(remoteAddress, protocol)) {
            promise.completeExceptionally(
                    new SessionProtocolNegotiationException(protocol, "previously failed negotiation"));
            return;
        }

        // Create a new connection.
        final EventLoop eventLoop = eventLoop();
        final Promise<Channel> sessionPromise = eventLoop.newPromise();
        connect(eventLoop, remoteAddress, poolKey.protocol, sessionPromise);

        if (sessionPromise.isDone()) {
            notifyConnect(poolKey, sessionPromise, promise);
        } else {
            sessionPromise.addListener((Future<Channel> future) -> notifyConnect(poolKey, future, promise));
        }
    }

    private void notifyConnect(PoolKey poolKey, Future<Channel> future,
                               CompletableFuture<PooledChannel> promise) {
        assert future.isDone();
        try {
            if (future.isSuccess()) {
                final Channel channel = future.getNow();
                final SessionProtocol protocol = getProtocolIfHealthy(channel);
                /*if (closed || protocol == null) {*/
                if (protocol == null) {
                    channel.close();
                    promise.completeExceptionally(
                            new UnprocessedRequestException(ClosedSessionException.get()));
                    return;
                }

                /*allChannels.put(channel, Boolean.TRUE);*/

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
                        promise.complete(new Http2PooledChannel(channel, protocol));
                    } else {
                        promise.complete(new Http1PooledChannel(channel, protocol, poolKey));
                    }
                } else {
                    // Server set MAX_CONCURRENT_STREAMS to 0, which means we can't send anything.
                    channel.close();
                    promise.completeExceptionally(
                            new UnprocessedRequestException(RefusedStreamException.get()));
                }

                channel.closeFuture().addListener(f -> {
                    /*allChannels.remove(channel);*/

                    /*// Clean up old unhealthy channels by iterating from the beginning of the queue.
                    final Deque<PooledChannel> queue = getPool(protocol, poolKey);
                    if (queue != null) {
                        for (;;) {
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
                    }*/
                });
            } else {
                promise.completeExceptionally(new UnprocessedRequestException(future.cause()));
            }
        } catch (Exception e) {
            promise.completeExceptionally(new UnprocessedRequestException(e));
        }
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

    private EventLoop eventLoop() {
        return eventLoops.get(0);
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

        ch.pipeline().addLast(new HttpSessionHandler(this, ch, sessionPromise, timeoutFuture));
    }

    //    /**
//     * A low-level operation that triggers a new connection attempt. Used only by:
//     * <ul>
//     *   <li>{@link #connect(SessionProtocol, HttpChannelPool.PoolKey, CompletableFuture, ClientConnectionTimingsBuilder)} -
//     *       The pool has been exhausted.</li>
//     *   <li>{@link HttpSessionHandler} - HTTP/2 upgrade has failed.</li>
//     * </ul>
//     */
    void connect(EventLoop eventLoop, SocketAddress remoteAddress, SessionProtocol desiredProtocol,
                 Promise<Channel> sessionPromise) {
        final Bootstrap bootstrap = bootstrap(eventLoop, desiredProtocol);
        final ChannelFuture connectFuture = bootstrap.connect(remoteAddress);

        connectFuture.addListener((ChannelFuture future) -> {
            if (future.isSuccess()) {
                initSession(desiredProtocol, future, sessionPromise);
            } else {
                sessionPromise.setFailure(future.cause());
            }
        });
    }

    private Bootstrap bootstrap(EventLoop eventLoop, SessionProtocol desiredProtocol) {
        final Bootstrap bootstrap = clientFactory.newBootstrap();
        bootstrap.group(eventLoop);
        bootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ch.pipeline().addLast(
                        new HttpClientPipelineConfigurator(clientFactory, desiredProtocol));
            }
        });
        return clientFactory.newBootstrap();
    }

    private static InetSocketAddress toRemoteAddress(PoolKey key) throws UnknownHostException {
        final InetAddress inetAddr = InetAddress.getByAddress(
                key.host, NetUtil.createByteArrayFromIpAddressString(key.ipAddr));
        return new InetSocketAddress(inetAddr, key.port);
    }

    private void finishResolve(String host, Endpoint endpoint, SessionProtocol protocol,
                               Future<InetSocketAddress> resolveFuture, CompletableFuture<Pool> result) {
        if (resolveFuture.isSuccess()) {
            final CompletableFuture<Pool> future =
                    pool(new PoolKey(protocol, host, resolveFuture.getNow().getAddress().getHostAddress(),
                                     endpoint.port()));
            future.handle((pool, cause) -> {
                if (cause != null) {
                    result.completeExceptionally(cause);
                } else {
                    result.complete(pool);
                }
                return null;
            });
        } else {
            result.completeExceptionally(resolveFuture.cause());
        }
    }

    @VisibleForTesting
    static String extractHost(HttpRequest req, Endpoint endpoint) {
        final String host = extractHost(req.authority());
        if (host != null) {
            return host;
        }

        return endpoint.host();
    }

    @Nullable
    private static String extractHost(@Nullable String authority) {
        if (Strings.isNullOrEmpty(authority)) {
            return null;
        }

        if (authority.charAt(0) == '[') {
            // Surrounded by '[' and ']'
            final int closingBracketPos = authority.lastIndexOf(']');
            if (closingBracketPos > 0) {
                return authority.substring(1, closingBracketPos);
            } else {
                // Invalid authority - no matching ']'
                return null;
            }
        }

        // Not surrounded by '[' and ']'
        final int colonPos = authority.lastIndexOf(':');
        if (colonPos > 0) {
            // Strip the port number.
            return authority.substring(0, colonPos);
        }
        if (colonPos < 0) {
            // authority does not have a port number.
            return authority;
        }

        // Invalid authority - ':' is the first character.
        return null;
    }

    private EventLoop resolverEventLoop(Endpoint endpoint) {
        return eventLoops.get(endpoint.hashCode() % eventLoops.size());
    }

    static final class PoolKey {
        final SessionProtocol protocol;
        final String host;
        final String ipAddr;
        final int port;
        final int hashCode;

        PoolKey(SessionProtocol protocol, String host, String ipAddr, int port) {
            this.protocol = protocol;
            this.host = host;
            this.ipAddr = ipAddr;
            this.port = port;
            hashCode = ((protocol.hashCode() * 31 + host.hashCode()) * 31 + ipAddr.hashCode()) * 31 + port;
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
                   port == that.port &&
                   host.equals(that.host) &&
                   protocol == that.protocol;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("protocol", protocol)
                              .add("host", host)
                              .add("ipAddr", ipAddr)
                              .add("port", port)
                              .toString();
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

    static final class Http1PooledChannel extends PooledChannel {
        private final PoolKey key;

        Http1PooledChannel(Channel channel, SessionProtocol protocol, PoolKey key) {
            super(channel, protocol);
            this.key = key;
        }

        @Override
        public void release() {
            if (isHealthy(this)) {
                // Channel turns out to be healthy. Add it back to the pool.
                /*addToPool(protocol(), key, this);*/
            }
        }
    }
}
