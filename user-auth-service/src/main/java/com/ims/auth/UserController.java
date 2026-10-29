package com.ims.auth;

import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.ims.common.security.Caller;
import com.ims.common.security.Roles;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class UserController {

    record RoleRequest(@NotBlank String role) {
    }

    /** Partial update: omitted fields are left unchanged; an empty email clears it. */
    record ProfileRequest(@Size(min = 1, max = 120) String fullName, @Email @Size(max = 255) String email) {
    }

    record UserView(UUID id, String fullName, String phone, String email, String role) {

        static UserView of(User u) {
            return new UserView(u.getId(), u.getFullName(), u.getPhone(), u.getEmail(), u.getRole());
        }
    }

    record RoleView(UUID userId, String role) {
    }

    private static final Set<String> ROLES = Set.of(Roles.CUSTOMER, Roles.STORE_OPS, Roles.RIDER, Roles.ADMIN);

    private final UserRepository users;
    private final RefreshTokenService refreshTokens;

    UserController(UserRepository users, RefreshTokenService refreshTokens) {
        this.users = users;
        this.refreshTokens = refreshTokens;
    }

    @GetMapping("/api/v1/users/me")
    UserView me(Caller caller) {
        return UserView.of(load(caller.userId()));
    }

    @PatchMapping("/api/v1/users/me")
    @Transactional
    UserView updateMe(Caller caller, @Valid @RequestBody ProfileRequest req) {
        User user = load(caller.userId());
        if (req.email() != null && !req.email().isBlank() && users.existsByEmailAndIdNot(req.email(), user.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use");
        }
        user.updateProfile(req.fullName(), req.email());
        return UserView.of(user);
    }

    /** "Log out everywhere": revokes every refresh token of the caller. */
    @DeleteMapping("/api/v1/users/me/sessions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logoutEverywhere(Caller caller) {
        refreshTokens.revokeAll(caller.userId());
    }

    /** Takes effect on the user's next login / token refresh. */
    @PutMapping("/api/v1/users/{id}/role")
    @Transactional
    RoleView changeRole(Caller caller, @PathVariable UUID id, @Valid @RequestBody RoleRequest req) {
        caller.requireAnyRole(Roles.ADMIN);
        if (!ROLES.contains(req.role())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown role " + req.role());
        }
        User user = load(id);
        user.setRole(req.role());
        return new RoleView(user.getId(), user.getRole());
    }

    private User load(UUID id) {
        return users.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }
}
