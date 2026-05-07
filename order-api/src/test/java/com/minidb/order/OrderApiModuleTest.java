package com.minidb.order;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OrderApiModuleTest {
    @Test
    void exposesModulePurpose() {
        assertEquals("order-fulfillment-consistency-lab", OrderApiModule.purpose());
    }
}
