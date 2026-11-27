package com.firstfood.common.web;

import java.io.IOException;

/** Thrown from the request stream when a body without a declared length grows past the configured maximum. */
public class RequestBodyTooLargeException extends IOException {

    public RequestBodyTooLargeException(long maxBytes) {
        super("Request body exceeds " + maxBytes + " bytes");
    }
}
