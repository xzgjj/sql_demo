package com.minidb.proxy;

public record ProxyConfig(
        int listenPort,
        String proxyUsername,
        String proxyPassword,
        String backendUsername,
        String backendPassword,
        int backendPoolMaxSize,
        long borrowTimeoutMs,
        long idleTimeoutMs,
        long readAfterWriteWindowMs,
        int shardCount,
        int backendConnectTimeoutMs,
        String backendHost,
        int backendPortBase
) {
    public static ProxyConfig defaults() {
        return new ProxyConfig(
                3306,
                "proxy",
                "proxy123",
                "root",
                "root123",
                16,
                5000,
                600_000,
                3000,
                4,
                5000,
                "127.0.0.1",
                3307
        );
    }

    /** Host:port for PRIMARY data source */
    public String primaryHost() { return backendHost; }
    public int primaryPort() { return backendPortBase; }

    /** Host:port for REPLICA data source */
    public int replicaPort() { return backendPortBase + 1; }

    /** Host:port for shard N */
    public int shardPort(int index) { return backendPortBase + 2 + index; }

    public static class BackendServer {
        public final String host;
        public final int port;
        public final String username;
        public final String password;

        public BackendServer(String host, int port, String username, String password) {
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
        }
    }
}
