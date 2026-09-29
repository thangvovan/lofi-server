package com.thangvovan.lofiserver.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;

/**
 * Swallows the exceptions a listener leaving mid-stream produces.
 */
@RestControllerAdvice(assignableTypes = StreamController.class)
class DisconnectHandler {

    @ExceptionHandler({AsyncRequestNotUsableException.class, IOException.class})
    @ResponseStatus(HttpStatus.OK)
    void listenerLeft() {
        // Nothing to do
    }
}
