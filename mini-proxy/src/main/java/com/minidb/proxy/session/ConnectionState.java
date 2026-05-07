package com.minidb.proxy.session;

public enum ConnectionState {
    HANDSHAKING,  // waiting for client HandshakeResponse41
    AUTH_DONE,    // authenticated, waiting for first command
    READY,        // processing commands
    CLOSING       // shutting down
}
