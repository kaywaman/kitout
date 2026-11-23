package org.kitout.application.port;

import java.util.Map;

/** Reads JSON text into, and writes it from, plain maps, lists, strings, numbers and booleans, for parsers that stay framework-free. */
public interface JsonReader {

    /** @throws IllegalArgumentException with the position of a syntax error */
    Map<String, Object> readObject(String json);

    /** Writes maps, lists and plain values as indented JSON, keeping the order of the maps. */
    String writePretty(Object value);
}
