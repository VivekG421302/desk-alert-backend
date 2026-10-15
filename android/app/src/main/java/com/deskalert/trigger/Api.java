package com.deskalert.trigger;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

final class Api {
    static final String BASE = "https://desk-alert-backend.vercel.app";

    static final class Result {
        final int code; final String body;
        Result(int code, String body) { this.code = code; this.body = body; }
        boolean ok() { return code >= 200 && code < 300; }
    }

    /** Blocking: call off the main thread. */
    static Result request(String method, String path, String password, String jsonBody) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestProperty("x-app-password", password);
            if (jsonBody != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                OutputStream os = c.getOutputStream();
                os.write(jsonBody.getBytes("UTF-8"));
                os.close();
            }
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            if (is != null) { byte[] buf = new byte[2048]; int n; while ((n = is.read(buf)) > 0) bo.write(buf, 0, n); is.close(); }
            return new Result(code, bo.toString("UTF-8"));
        } finally { c.disconnect(); }
    }
}
