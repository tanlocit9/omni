-- Auth & multi-tenant user management

CREATE TABLE users (
    id              SERIAL PRIMARY KEY,
    email           TEXT        NOT NULL UNIQUE,
    password_hash   TEXT,                          -- NULL for OAuth users
    display_name    TEXT,
    role            TEXT        NOT NULL DEFAULT 'user', -- user | admin | readonly
    is_active       BOOLEAN     NOT NULL DEFAULT TRUE,
    settings_json   JSONB,                         -- theme, language, notification preferences
    timezone        TEXT        NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
    last_login_at   TIMESTAMP,
    created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_users_email ON users(email);
