package com.quantumlock.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Writes the short, fixed JSON body the security filters send when they refuse a request.
 * The body never contains anything taken from the request, so it cannot be used to
 * reflect attacker-controlled text back to a browser.
 */
final class Rejections {

    private Rejections() {
    }

    static void write(HttpServletResponse response, int status, String errorCode) throws IOException {
        byte[] body = ("{\"error\":\"" + errorCode + "\"}").getBytes(StandardCharsets.UTF_8);
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
