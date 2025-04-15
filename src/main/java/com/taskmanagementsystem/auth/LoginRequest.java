package com.taskmanagementsystem.auth;

public record LoginRequest(
        String email,
        String password
) {
}
