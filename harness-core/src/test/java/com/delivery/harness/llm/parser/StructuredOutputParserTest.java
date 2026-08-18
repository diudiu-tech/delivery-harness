package com.delivery.harness.llm.parser;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredOutputParserTest {

    private final StructuredOutputParser parser = new StructuredOutputParser();

    @Test
    void extractsAndParsesFencedJson() {
        String output = "Result:\n```json\n{\"name\":\"delivery\",\"count\":2}\n```\nDone.";

        assertEquals("{\"name\":\"delivery\",\"count\":2}", parser.extractJson(output));

        JsonNode parsed = parser.parse(output, JsonNode.class);
        assertAll(
                () -> assertEquals("delivery", parsed.get("name").asText()),
                () -> assertEquals(2, parsed.get("count").asInt())
        );
    }

    @Test
    void extractsAndParsesRawJsonEmbeddedInText() {
        String output = "The structured result is {\"success\":true,\"reason\":\"matched\"}.";

        assertEquals("{\"success\":true,\"reason\":\"matched\"}", parser.extractJson(output));

        JsonNode parsed = parser.parse(output, JsonNode.class);
        assertAll(
                () -> assertTrue(parsed.get("success").asBoolean()),
                () -> assertEquals("matched", parsed.get("reason").asText()),
                () -> assertFalse(parsed.has("missing"))
        );
    }

    @Test
    void rejectsOutputWithoutValidJson() {
        String output = "The model returned {not valid JSON}.";

        assertNull(parser.extractJson(output));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(output, JsonNode.class));
    }

    @Test
    void handlesNullAndBlankOutput() {
        assertAll(
                () -> assertNull(parser.extractJson(null)),
                () -> assertNull(parser.extractJson("  \n  ")),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> parser.parse(null, JsonNode.class)
                )
        );
    }
}
