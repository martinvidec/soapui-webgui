package de.soapuiweb.storage;

public record UserAccount(
        String login,
        String passwordHash,
        UserRole role,
        boolean active,
        boolean mustChangePassword) {

    public UserAccount withPasswordHash(String newHash, boolean mustChange) {
        return new UserAccount(login, newHash, role, active, mustChange);
    }

    public UserAccount withActive(boolean newActive) {
        return new UserAccount(login, passwordHash, role, newActive, mustChangePassword);
    }
}
