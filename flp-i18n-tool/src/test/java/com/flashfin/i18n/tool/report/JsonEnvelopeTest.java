package com.flashfin.i18n.tool.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class JsonEnvelopeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void serializesSuccessEnvelope() throws Exception {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("moduleCount", 2);

        JsonNode node = mapper.readTree(Reporter.toJson(JsonEnvelope.success("status", data)));

        assertTrue(node.get("ok").asBoolean());
        assertEquals("status", node.get("command").asText());
        assertEquals(2, node.get("data").get("moduleCount").asInt());
        assertEquals(0, node.get("warnings").size());
        assertTrue(node.has("error"));
        assertTrue(node.get("error").isNull());
    }

    @Test
    public void serializesErrorEnvelope() throws Exception {
        ErrorInfo error = new ErrorInfo("TOKEN_MISSING", "missing", "export it", false);

        JsonNode node = mapper.readTree(Reporter.toJson(JsonEnvelope.failure("status", null, error)));

        assertFalse(node.get("ok").asBoolean());
        assertEquals("status", node.get("command").asText());
        assertTrue(node.get("data").isNull());
        assertNotNull(node.get("error"));
        assertEquals("TOKEN_MISSING", node.get("error").get("code").asText());
        assertEquals("missing", node.get("error").get("message").asText());
        assertEquals("export it", node.get("error").get("hint").asText());
        assertFalse(node.get("error").get("retryable").asBoolean());
    }
}
