package com.minidb.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MiniProxyModuleTest {
    @Test
    void exposesModulePurpose() {
        assertEquals("mysql-proxy-routing-lab", MiniProxyModule.purpose());
    }
}
