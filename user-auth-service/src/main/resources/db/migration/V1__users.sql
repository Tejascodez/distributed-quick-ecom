CREATE TABLE users (
    id            UUID PRIMARY KEY,
    full_name     VARCHAR(120) NOT NULL,
    phone         VARCHAR(20)  NOT NULL UNIQUE,
    email         VARCHAR(255) UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'CUSTOMER',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
