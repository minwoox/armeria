package com.linecorp.armeria.client;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.client.HttpChannelPool.PoolKey;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.internal.PathAndQuery;

import io.netty.channel.EventLoop;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

public class ConnectionUtil {

    CompletableFuture<PooledChannel> pooledChannel(
            ClientRequestContext ctx, HttpClientFactory factory,
            AddressResolverGroup<InetSocketAddress> addressResolverGroup) {
//        if (!isValidPath(req)) {
//            final IllegalArgumentException cause = new IllegalArgumentException("invalid path: " + req.path());
//            handleEarlyRequestException(ctx, req, cause);
//            return;
//        }

        final Endpoint endpoint = ctx.endpoint();
        assert endpoint != null;
        final EventLoop eventLoop = ctx.eventLoop();

        final ClientConnectionTimingsBuilder timingsBuilder = new ClientConnectionTimingsBuilder();

        if (endpoint.hasIpAddr()) {
            final String ipAddr = endpoint.ipAddr();
            assert ipAddr != null;
            acquireConnection(ctx, factory, endpoint, ipAddr, timingsBuilder);
        }

        // IP address has not been resolved yet.
        final Future<InetSocketAddress> resolveFuture =
                addressResolverGroup.getResolver(eventLoop)
                                    .resolve(InetSocketAddress.createUnresolved(endpoint.host(),
                                                                                endpoint.port()));
        if (resolveFuture.isDone()) {
            return finishResolve(ctx, factory, endpoint, resolveFuture, timingsBuilder);
        }

        final CompletableFuture<PooledChannel> result = new CompletableFuture<>();
        resolveFuture.addListener((FutureListener<InetSocketAddress>) future -> {
            final CompletableFuture<PooledChannel> resolve =
                    finishResolve(ctx, factory, endpoint, future, timingsBuilder);
            resolve.handle((pooledChannel, cause) -> {
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

    private static CompletableFuture<PooledChannel> acquireConnection(
            ClientRequestContext ctx, HttpClientFactory factory, Endpoint endpoint, String ipAddr,
            ClientConnectionTimingsBuilder timingsBuilder) {
        final EventLoop eventLoop = ctx.eventLoop();
        if (!eventLoop.inEventLoop()) {
            final CompletableFuture<PooledChannel> result = new CompletableFuture<>();
            eventLoop.submit(() -> acquireConnection(ctx, factory, endpoint, ipAddr, timingsBuilder))
                     .addListener((FutureListener<CompletableFuture<PooledChannel>>) f -> {
                         if (f.isSuccess()) {
                             f.getNow().handle((pooledChannel, cause) -> {
                                 if (cause != null) {
                                     result.completeExceptionally(cause);
                                 } else {
                                     result.complete(pooledChannel);
                                 }
                                 return null;
                             });
                         } else {
                             result.completeExceptionally(f.cause());
                         }
                     });
            return result;
        }
        final int port = endpoint.port();
        final SessionProtocol protocol = ctx.sessionProtocol();
        final HttpChannelPool pool = factory.pool(ctx.eventLoop());

        final PoolKey key = new PoolKey(ipAddr, port);
        final PooledChannel pooledChannel = pool.acquireNow(protocol, key);
        if (pooledChannel != null) {
            return CompletableFuture.completedFuture(pooledChannel);
        } else {
            return pool.acquireLater(protocol, key, timingsBuilder);
        }
    }

    private CompletableFuture<PooledChannel> finishResolve(ClientRequestContext ctx, HttpClientFactory factory,
                                                           Endpoint endpoint,
                                                           Future<InetSocketAddress> resolveFuture,
                                                           ClientConnectionTimingsBuilder timingsBuilder) {
        timingsBuilder.dnsResolutionEnd();
        if (resolveFuture.isSuccess()) {
            final String ipAddr = resolveFuture.getNow().getAddress().getHostAddress();
            return acquireConnection(ctx, factory, endpoint, ipAddr, timingsBuilder);
        }

        timingsBuilder.build().setTo(ctx);
        return CompletableFutures.exceptionallyCompletedFuture(resolveFuture.cause());
    }
}
