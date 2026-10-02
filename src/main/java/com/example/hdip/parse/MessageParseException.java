package com.example.hdip.parse;

public class MessageParseException extends IllegalArgumentException {
    public MessageParseException(String message) {
        super(message);
    }

    public MessageParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
