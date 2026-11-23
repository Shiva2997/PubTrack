package com.pubtrack.model;

public record User(int id, String fullName, String email, Role role) {

    public boolean is(Role r) {
        return role == r;
    }
}
