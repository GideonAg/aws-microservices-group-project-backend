package com.taskmanagementsystem.util;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PasswordGenerator {

    private static final int PASSWORD_LENGTH = 8;
    private static final String UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWERCASE = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGITS = "0123456789";
    private static final String SPECIAL = "!@#$%^&*()-_=+[]{}|;:,.<>?";

    private static final SecureRandom random = new SecureRandom();

    public static String generatePassword() {
        List<Character> passwordChars = new ArrayList<>();

        // Ensure each required character type is included at least once
        passwordChars.add(randomChar(UPPERCASE));
        passwordChars.add(randomChar(LOWERCASE));
        passwordChars.add(randomChar(DIGITS));
        passwordChars.add(randomChar(SPECIAL));

        // Fill the rest of the password
        String allChars = UPPERCASE + LOWERCASE + DIGITS + SPECIAL;
        for (int i = passwordChars.size(); i < PASSWORD_LENGTH; i++) {
            passwordChars.add(randomChar(allChars));
        }

        // Shuffle to avoid predictable pattern
        Collections.shuffle(passwordChars);

        // Convert to string
        StringBuilder password = new StringBuilder();
        for (char ch : passwordChars) {
            password.append(ch);
        }

        return password.toString();
    }

    private static char randomChar(String charSet) {
        return charSet.charAt(random.nextInt(charSet.length()));
    }
}
