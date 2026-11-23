package org.kitout.infrastructure.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kitout.application.port.JsonReader;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;

@ApplicationScoped
public class JacksonJsonReader implements JsonReader {

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {
    };

    private final ObjectMapper json;

    public JacksonJsonReader(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public Map<String, Object> readObject(String text) {
        try {
            return json.readValue(text, OBJECT);
        } catch (JsonProcessingException e) {
            var loc = e.getLocation();
            throw new IllegalArgumentException("Not valid JSON" + (loc == null ? "" : " at line " + loc.getLineNr() + ", column " + loc.getColumnNr())
                    + ": " + e.getOriginalMessage());
        }
    }

    @Override
    public String writePretty(Object value) {
        try {
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot write JSON: " + e.getOriginalMessage(), e);
        }
    }
}
