package com.ims.auth;

import com.ims.common.security.Roles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Promotes BOOTSTRAP_ADMIN_PHONE's account to ADMIN so the first admin can manage the rest via the API. */
@Component
class AdminBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final String adminPhone;

    AdminBootstrap(UserRepository users, @Value("${ims.bootstrap-admin-phone}") String adminPhone) {
        this.users = users;
        this.adminPhone = adminPhone;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void promote() {
        if (adminPhone.isBlank()) {
            return;
        }
        users.findByPhone(adminPhone).filter(u -> !Roles.ADMIN.equals(u.getRole())).ifPresent(u -> {
            u.setRole(Roles.ADMIN);
            log.info("Promoted bootstrap admin {}", u.getId());
        });
    }
}
