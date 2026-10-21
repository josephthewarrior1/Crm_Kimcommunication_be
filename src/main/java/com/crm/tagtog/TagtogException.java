package com.crm.tagtog;

public class TagtogException extends RuntimeException {
    public final int status;
    public final String code;
    public TagtogException(int status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
}
