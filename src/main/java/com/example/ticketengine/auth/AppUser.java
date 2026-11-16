package com.example.ticketengine.auth;

/** A row of the users table. */
public record AppUser(long id, String email, String passwordHash, Role role) {

    /**
     * The value stored in reservations.user_id for this account. The "acct-" prefix keeps account
     * bookings apart from the free-text user ids the public benchmark endpoint accepts.
     */
    public String reservationUserId() { return reservationUserId(id); }

    public static String reservationUserId(long accountId) { return "acct-" + accountId; }
}
