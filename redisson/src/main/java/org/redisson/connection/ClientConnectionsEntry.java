/**
 * Copyright (c) 2013-2024 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson.connection;

import io.netty.channel.ChannelFuture;
import org.redisson.api.NodeType;
import org.redisson.client.RedisClient;
import org.redisson.client.RedisConnection;
import org.redisson.client.RedisPubSubConnection;
import org.redisson.client.protocol.CommandData;
import org.redisson.config.MasterSlaveServersConfig;
import org.redisson.config.ReadMode;
import org.redisson.misc.WrappedLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 
 * @author Nikita Koksharov
 *
 */
public class ClientConnectionsEntry {

    final Logger log = LoggerFactory.getLogger(getClass());

    private final ConnectionsHolder<RedisConnection> connectionsHolder;

    private final ConnectionsHolder<RedisPubSubConnection> pubSubConnectionsHolder;

    public enum FreezeReason {MANAGER, RECONNECT, SYSTEM}

    private volatile FreezeReason freezeReason;
    final RedisClient client;

    private volatile NodeType nodeType;
    private final IdleConnectionWatcher idleConnectionWatcher;
    private final ConnectionManager connectionManager;

    private final MasterSlaveServersConfig config;

    private volatile boolean initialized = false;

    private final WrappedLock lock = new WrappedLock();

    public ClientConnectionsEntry(RedisClient client, int poolMinSize, int poolMaxSize,
                                  ConnectionManager connectionManager, NodeType nodeType, MasterSlaveServersConfig config) {
        this.client = client;
        this.connectionsHolder = new ConnectionsHolder<>(client, poolMaxSize, r -> r.connectAsync(),
                connectionManager.getServiceManager(), true);
        this.idleConnectionWatcher = connectionManager.getServiceManager().getConnectionWatcher();
        this.connectionManager = connectionManager;
        this.nodeType = nodeType;
        this.config = config;
        this.pubSubConnectionsHolder = new ConnectionsHolder<>(client, config.getSubscriptionConnectionPoolSize(),
                r -> r.connectPubSubAsync(), connectionManager.getServiceManager(), false);

        if (config.getSubscriptionConnectionPoolSize() > 0) {
            idleConnectionWatcher.add(this, config.getSubscriptionConnectionMinimumIdleSize(),
                                                config.getSubscriptionConnectionPoolSize(), pubSubConnectionsHolder);
        }
        idleConnectionWatcher.add(this, poolMinSize, poolMaxSize, connectionsHolder);
    }

    public CompletableFuture<Void> initConnections(int minimumIdleSize) {
        return connectionsHolder.initConnections(minimumIdleSize);
    }

    public CompletableFuture<Void> initPubSubConnections(int minimumIdleSize) {
        return pubSubConnectionsHolder.initConnections(minimumIdleSize);
    }

    public boolean isMasterForRead() {
        return getFreezeReason() == FreezeReason.SYSTEM
                        && config.getReadMode() == ReadMode.MASTER_SLAVE
                            && getNodeType() == NodeType.MASTER;
    }

    public boolean isInitialized() {
        return this.initialized;
    }

    public void setInitialized(boolean isInited) {
        this.initialized = isInited;
    }
    
    public void setNodeType(NodeType nodeType) {
        this.nodeType = nodeType;
    }

    public NodeType getNodeType() {
        return nodeType;
    }

    public CompletableFuture<Void> shutdownAsync() {
        idleConnectionWatcher.remove(this);
        return client.shutdownAsync().toCompletableFuture();
    }

    public RedisClient getClient() {
        return client;
    }

    public boolean isFreezed() {
        return freezeReason != null;
    }

    public void setFreezeReason(FreezeReason freezeReason) {
        this.freezeReason = freezeReason;
        if (freezeReason != null) {
            this.initialized = false;
        }
    }

    public FreezeReason getFreezeReason() {
        return freezeReason;
    }

    public WrappedLock getLock() {
        return lock;
    }

    public void reattachPubSub() {
        pubSubConnectionsHolder.getFreeConnectionsCounter().removeListeners();

        for (RedisPubSubConnection connection : pubSubConnectionsHolder.getAllConnections()) {
            connection.closeAsync();
            connectionManager.getSubscribeService().reattachPubSub(connection);
        }
        pubSubConnectionsHolder.getFreeConnections().clear();
        pubSubConnectionsHolder.getAllConnections().clear();
    }

    public void nodeDown() {
        connectionsHolder.getFreeConnectionsCounter().removeListeners();

        for (RedisConnection connection : connectionsHolder.getAllConnections()) {
            connection.closeAsync();
            reattachBlockingQueue(connection.getCurrentCommand());
        }
        connectionsHolder.getFreeConnections().clear();
        connectionsHolder.getAllConnections().clear();

        reattachPubSub();
    }

    private void reattachBlockingQueue(CommandData<?, ?> commandData) {
        if (commandData == null
                || !commandData.isBlockingCommand()
                || commandData.getPromise().isDone()) {
            return;
        }

        String key = null;
        for (int i = 0; i < commandData.getParams().length; i++) {
            Object param = commandData.getParams()[i];
            if ("STREAMS".equals(param)) {
                key = (String) commandData.getParams()[i+1];
                break;
            }
        }
        if (key == null) {
            key = (String) commandData.getParams()[0];
        }

        MasterSlaveEntry entry = connectionManager.getEntry(key);
        if (entry == null) {
            connectionManager.getServiceManager().newTimeout(timeout ->
                    reattachBlockingQueue(commandData), 1, TimeUnit.SECONDS);
            return;
        }

        CompletableFuture<RedisConnection> newConnectionFuture = entry.connectionWriteOp(commandData.getCommand());
        newConnectionFuture.whenComplete((newConnection, e) -> {
            if (e != null) {
                connectionManager.getServiceManager().newTimeout(timeout ->
                        reattachBlockingQueue(commandData), 1, TimeUnit.SECONDS);
                return;
            }

            commandData.getPromise().whenComplete((r, ex) -> {
                entry.releaseWrite(newConnection);
            });

            ChannelFuture channelFuture = newConnection.send(commandData);
            channelFuture.addListener(future -> {
                if (!future.isSuccess()) {
                    connectionManager.getServiceManager().newTimeout(timeout ->
                            reattachBlockingQueue(commandData), 1, TimeUnit.SECONDS);
                    return;
                }
                log.info("command '{}' has been resent to '{}'", commandData, newConnection.getRedisClient());
            });
        });
    }

    public ConnectionsHolder<RedisConnection> getConnectionsHolder() {
        return connectionsHolder;
    }

    public ConnectionsHolder<RedisPubSubConnection> getPubSubConnectionsHolder() {
        return pubSubConnectionsHolder;
    }

    @Override
    public String toString() {
        return "ClientConnectionsEntry{" +
                "connectionsHolder=" + connectionsHolder +
                ", pubSubConnectionsHolder=" + pubSubConnectionsHolder +
                ", freezeReason=" + freezeReason +
                ", client=" + client +
                ", nodeType=" + nodeType +
                ", initialized=" + initialized +
                '}';
    }
}

