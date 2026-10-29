ALTER TABLE users
    ADD CONSTRAINT chk_users_role CHECK (role IN ('CUSTOMER', 'STORE_OPS', 'RIDER', 'ADMIN'));
