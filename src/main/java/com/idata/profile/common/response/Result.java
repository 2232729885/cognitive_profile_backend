package com.idata.profile.common.response;

import lombok.Data;

@Data
public class Result<T> {
    private boolean success;
    private String code;
    private String message;
    private T data;

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.setSuccess(true);
        r.setData(data);
        return r;
    }

    public static <T> Result<T> fail(String code, String message) {
        Result<T> r = new Result<>();
        r.setSuccess(false);
        r.setCode(code);
        r.setMessage(message);
        return r;
    }
}
