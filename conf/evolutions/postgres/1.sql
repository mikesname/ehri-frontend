# --- !Ups

CREATE TABLE users (
    id          VARCHAR(50) NOT NULL PRIMARY KEY,
    email       VARCHAR(255) NOT NULL,
    verified    BOOLEAN NOT NULL DEFAULT FALSE,
    staff       BOOLEAN NOT NULL DEFAULT FALSE,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    allow_messaging BOOLEAN NOT NULL DEFAULT TRUE,
    created     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login  TIMESTAMP NULL,
    password    VARCHAR(255) NULL,
    is_legacy   BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX users_email ON users (email);

CREATE TABLE openid_association (
    id           VARCHAR(50) NOT NULL,
    openid_url   VARCHAR(255) NOT NULL,
    created      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(id, openid_url)
);

ALTER TABLE openid_association ADD CONSTRAINT openid_association_id FOREIGN KEY (id) REFERENCES users (id) ON DELETE CASCADE;

CREATE TABLE oauth2_association (
    id           VARCHAR(50) NOT NULL,
    provider_id  VARCHAR(255) NOT NULL,
    provider     VARCHAR(255) NOT NULL,
    created      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(id, provider_id, provider)
);

ALTER TABLE oauth2_association ADD CONSTRAINT oauth2_association_id FOREIGN KEY (id) REFERENCES users (id) ON DELETE CASCADE;

CREATE TABLE token (
  id          VARCHAR(50) NOT NULL,
  token       VARCHAR(255) NOT NULL PRIMARY KEY,
  expires     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  is_sign_up  BOOLEAN NOT NULL DEFAULT FALSE
);

ALTER TABLE token ADD CONSTRAINT token_profile_id FOREIGN KEY (id) REFERENCES users (id) ON DELETE CASCADE;

CREATE TABLE user_auth_token(
    id          VARCHAR(50) NOT NULL,
    token       VARCHAR(255) NOT NULL PRIMARY KEY,
    expires     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE user_auth_token ADD CONSTRAINT auth_auth_token_profile_id FOREIGN KEY (id) REFERENCES users (id) ON DELETE CASCADE;

CREATE TABLE moved_pages(
    original_path_sha1 CHAR(40) PRIMARY KEY,
    original_path TEXT NOT NULL,
    new_path      TEXT NOT NULL,
    created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE research_guide (
    id SERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    path VARCHAR(255) DEFAULT NULL,
    picture VARCHAR(255) DEFAULT NULL,
    virtual_unit VARCHAR(255) DEFAULT NULL,
    description TEXT,
    css TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    default_page INTEGER NULL
);

ALTER TABLE research_guide ADD CONSTRAINT research_guide_path_unique UNIQUE(path);

CREATE TABLE research_guide_page (
    id SERIAL PRIMARY KEY,
    research_guide_id INTEGER NOT NULL,
    name TEXT NOT NULL,
    layout VARCHAR(45) DEFAULT NULL,
    content TEXT,
    path VARCHAR(45) DEFAULT NULL,
    position VARCHAR(45) DEFAULT NULL,
    description TEXT,
    params VARCHAR(255) DEFAULT NULL
);

ALTER TABLE research_guide_page ADD CONSTRAINT research_guide_page_id FOREIGN KEY (research_guide_id) REFERENCES research_guide (id) ON DELETE CASCADE;
ALTER TABLE research_guide_page ADD CONSTRAINT research_guide_path_guide_id UNIQUE (research_guide_id, path);



# --- !Downs

DROP TABLE IF EXISTS research_guide_page;
DROP TABLE IF EXISTS research_guide;
DROP TABLE IF EXISTS user_auth_token;
DROP TABLE IF EXISTS token;
DROP TABLE IF EXISTS openid_association;
DROP TABLE IF EXISTS oauth2_association;
DROP TABLE IF EXISTS moved_pages;
DROP TABLE IF EXISTS users;
