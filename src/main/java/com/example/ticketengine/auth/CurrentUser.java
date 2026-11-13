package com.example.ticketengine.auth;

import org.springframework.security.oauth2.jwt.Jwt;

/** Reads the signed-in account from a verified JWT. */
public final class CurrentUser {

    private CurrentUser() {}

    public static long accountId(Jwt token) { return Long.parseLong(token.getSubject()); }

    /** The user_id written on this account's reservations. */
    public static String reservationUserId(Jwt token) { return AppUser.reservationUserId(accountId(token)); }
}
