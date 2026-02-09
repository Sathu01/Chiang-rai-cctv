package com.backendcam.backendcam.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

/**
 * Simple utility to write JSON responses.
 * Usage example:
 * ResponseWriter.writeJson(response, 401, Map.of("status",401, "message", "Unauthorized"));
 */
public final class ResponseWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ResponseWriter() {
        // utility
    }

    public static void writeJson(HttpServletResponse response, int status, Object body) {

        response.setStatus(status);
        response.setContentType("application/json");

        try {
            String json;
            if (body == null) {

                json = "{}";

            } else if (body instanceof String) {

                String s = ((String) body).trim();

                // if caller provided a JSON string, use it as-is; otherwise wrap as message
                if ((s.startsWith("{") && s.endsWith("}")) || (s.startsWith("[") && s.endsWith("]"))) {
                    json = (String) body;
                } else {
                    json = MAPPER.writeValueAsString(Map.of("message", body));
                }

            } else {
                json = MAPPER.writeValueAsString(body);
            }

            response.getWriter().write(json);

        } catch (IOException e) {

            try {
                response.getWriter().write("{\"status\":" + status + "}");
            } catch (IOException ignored) {}

        }
    }
}
