package com.linecorp.armeria.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.client.HttpChannelPoolScheduler.PoolKey;

import io.netty.channel.EventLoop;

final class Http2ChannelPool implements HttpConnectionPool {

    private final PoolKey poolKey;
    private final HttpChannelPoolScheduler scheduler;
    
    private final List<EventLoop> eventLoops;
    private final List<PooledChannel> channels = new ArrayList<>();

    private final List<CompletableFuture<PooledChannel>> pendingAcquisition = new ArrayList<>();

    private int allRequests;
    
    private int maxEventLoops = 5;

    Http2ChannelPool(PoolKey poolKey, HttpChannelPoolScheduler scheduler, List<EventLoop> eventLoops) {
        this.poolKey = poolKey;
        this.scheduler = scheduler;
        this.eventLoops = eventLoops;
    }

    @Override
    public void add(PooledChannel channel) {
        synchronized (channels) {
            channels.add(channel);
        }
    }

    @Override
    public CompletableFuture<PooledChannel> pooledChannel() {
        CompletableFuture<PooledChannel> promise;
        boolean connect = false;
        synchronized (channels) {
            if (allRequests > channels.size() + pendingAcquisition.size()) {
                connect = true;
                promise = new CompletableFuture<>();
                pendingAcquisition.add(promise);
            }

            if (!channels.isEmpty()) {
                promise = new CompletableFuture<>();
                promise.complete(channels.get(0));
            } else {
                promise = pendingAcquisition.get(0);
            }
            allRequests++;
            //bubbleDown();
        }

        promise.handle((pooledChannel, cause) -> {
            synchronized (channels) {
                allRequests++;
                pooledChannel
            }
            return null;
        });


        if (connect) {
            scheduler.connect(poolKey, promise);
        }
        return promise;
    }

    public void release(PooledChannel pooledChannel) {
        synchronized (channels) {
            allRequests--;
            channels.add(pooledChannel);
        }
    }

    private boolean connectUsingUnusedEventLoop() {
        final CompletableFuture<PooledChannel> future = new CompletableFuture<>();
        synchronized (pendingAcquisition) {
            if () {

            }
            scheduler.connect(poolKey, future);
            pendingAcquisition.add(future);


        }





        if (channels.size() < maxEventLoops) {

            final CompletableFuture<PooledChannel> future = new CompletableFuture<>();
            //scheduler.connect(poolKey, future);

            pendingAcquisition();
            //push(new Entry(this, eventLoops.get(nextUnusedEventLoopIdx), entries.size()));
            //nextUnusedEventLoopIdx = (nextUnusedEventLoopIdx + 1) % eventLoops.size();
            return true;
        } else {
            return false;
        }
    }

    private void pendingAcquisition() {
    }

    private void push(PooledChannel channel) {
        channels.add(channel);
        //bubbleUp(entries.size() - 1);
    }
}
