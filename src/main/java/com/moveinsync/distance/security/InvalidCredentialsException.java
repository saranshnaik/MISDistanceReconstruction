package com.moveinsync.distance.security;

/** Wrong username or password. Deliberately says nothing about which of the two was wrong. */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("invalid credentials");
    }
}
