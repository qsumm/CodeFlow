package com.ainative.integration.receipt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalOperationKeysTest {

    @Test
    void commentKeyIsStableAndScopedByWorkitemAndComment() {
        assertEquals(ExternalOperationKeys.comment("ZENTAO", 10L, 20L),
                ExternalOperationKeys.comment("ZENTAO", 10L, 20L));
        assertNotEquals(ExternalOperationKeys.comment("ZENTAO", 10L, 20L),
                ExternalOperationKeys.comment("ZENTAO", 10L, 21L));
        assertNotEquals(ExternalOperationKeys.comment("ZENTAO", 10L, 20L),
                ExternalOperationKeys.comment("ZENTAO", 11L, 20L));
    }

    @Test
    void markerDoesNotExposeTheOperationKey() {
        String operationKey = ExternalOperationKeys.comment("ZENTAO", 10L, 20L);
        String marker = ExternalOperationKeys.marker(operationKey);

        assertTrue(marker.matches("<!-- an-op:[0-9a-f]{24} -->"));
        assertTrue(!marker.contains(operationKey));
    }
}
