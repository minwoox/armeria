package com.linecorp.armeria.client;

import java.util.concurrent.CompletableFuture;

interface HttpConnectionPool {
    void add(PooledChannel channel);

    CompletableFuture<PooledChannel> pooledChannel();
}
