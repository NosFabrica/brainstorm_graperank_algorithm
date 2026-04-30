package com.nosfabrica.graperank.grape;

import com.nosfabrica.graperank.exceptions.ErrorCode;

public class GrapeRankError {
    private ErrorCode code;
    private String message;

    public GrapeRankError() {
    }

    public GrapeRankError(ErrorCode code, String message) {
        this.code = code;
        this.message = message;
    }

    public ErrorCode getCode() {
        return code;
    }

    public void setCode(ErrorCode code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
