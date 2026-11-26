package com.delivery.harness.gateway.platform.validation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;

import java.io.IOException;

/** JSON request scalars preserve their declared type instead of accepting string coercions. */
public final class StrictBoolean extends JsonDeserializer<Boolean> {
    @Override
    public Boolean deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_TRUE
                && parser.currentToken() != JsonToken.VALUE_FALSE) {
            throw JsonMappingException.from(parser, "Invalid JSON scalar type");
        }
        return parser.getBooleanValue();
    }
}
