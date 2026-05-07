package com.minidb.proxy.protocol;

/**
 * Parses the MySQL HandshakeResponse41 sent by the client after receiving HandshakeV10.
 */
public record HandshakeResponse41(
        int capabilityFlags,
        int maxPacketSize,
        byte characterSet,
        String username,
        byte[] authResponse,
        String database,
        String authPluginName
) {}
