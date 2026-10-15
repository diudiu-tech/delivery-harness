package com.delivery.harness.gateway.platform.validation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/** Timestamps use explicit ISO-8601 strings, never locale-dependent or numeric epoch coercions. */
public final class StrictInstant extends JsonDeserializer<Instant> {
    @Override
    public Instant deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            throw JsonMappingException.from(parser, "Expected an ISO-8601 timestamp string");
        }
        try {
            return Instant.parse(parser.getText());
        } catch (DateTimeParseException invalid) {
            throw JsonMappingException.from(parser, "Expected an ISO-8601 timestamp string");
        }
    }
}
