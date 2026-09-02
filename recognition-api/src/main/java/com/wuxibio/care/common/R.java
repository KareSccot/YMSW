package com.wuxibio.care.common;

public class R<T> {

    private int code;
    private String message;
    private T data;

    private R() {}

    public int getCode() { return code; }
    public String getMessage() { return message; }
    public T getData() { return data; }

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.code = 200;
        r.message = "ok";
        r.data = data;
        return r;
    }

    public static <T> R<T> fail(String message) {
        return fail(400, message);
    }

    public static <T> R<T> fail(int code, String message) {
        R<T> r = new R<>();
        r.code = code;
        r.message = message;
        return r;
    }
}
