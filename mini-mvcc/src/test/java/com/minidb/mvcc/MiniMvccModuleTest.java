package com.minidb.mvcc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MiniMvccModuleTest {
    @Test
    void exposesModulePurpose() {
        assertEquals("mvcc-visibility-lab", MiniMvccModule.purpose());
    }
}
