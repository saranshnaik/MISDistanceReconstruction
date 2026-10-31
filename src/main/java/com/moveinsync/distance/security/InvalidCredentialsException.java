package com.moveinsync.distance.security;

/** Wrong username or password; says nothing about which. */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("invalid credentials");
    }
}
