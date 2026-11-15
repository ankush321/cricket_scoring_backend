CREATE TABLE users (
    id UUID PRIMARY KEY,
    email TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    display_name TEXT NOT NULL,
    created_at_epoch_ms BIGINT NOT NULL,
    updated_at_epoch_ms BIGINT NOT NULL
);

CREATE TABLE players (
    id TEXT PRIMARY KEY,
    owner_user_id UUID NOT NULL REFERENCES users (id),
    name TEXT NOT NULL,
    role TEXT NOT NULL,
    photo_uri TEXT,
    version INTEGER NOT NULL DEFAULT 1,
    created_at_epoch_ms BIGINT NOT NULL,
    updated_at_epoch_ms BIGINT NOT NULL,
    deleted_at_epoch_ms BIGINT
);

CREATE INDEX players_owner_updated_idx ON players (owner_user_id, updated_at_epoch_ms);

CREATE TABLE teams (
    id TEXT PRIMARY KEY,
    owner_user_id UUID NOT NULL REFERENCES users (id),
    name TEXT NOT NULL,
    short_name TEXT NOT NULL,
    color INTEGER NOT NULL,
    captain_player_id TEXT,
    wicketkeeper_player_id TEXT,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    version INTEGER NOT NULL DEFAULT 1,
    created_at_epoch_ms BIGINT NOT NULL,
    updated_at_epoch_ms BIGINT NOT NULL,
    deleted_at_epoch_ms BIGINT
);

CREATE INDEX teams_owner_updated_idx ON teams (owner_user_id, updated_at_epoch_ms);

CREATE TABLE team_players (
    team_id TEXT NOT NULL REFERENCES teams (id),
    player_id TEXT NOT NULL REFERENCES players (id),
    position INTEGER NOT NULL,
    PRIMARY KEY (team_id, player_id)
);

CREATE TABLE rule_profiles (
    id TEXT PRIMARY KEY,
    owner_user_id UUID NOT NULL REFERENCES users (id),
    name TEXT NOT NULL,
    style TEXT NOT NULL,
    source TEXT NOT NULL,
    confirmed_by_organizer BOOLEAN NOT NULL DEFAULT FALSE,
    rules_json JSONB NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    created_at_epoch_ms BIGINT NOT NULL,
    updated_at_epoch_ms BIGINT NOT NULL,
    deleted_at_epoch_ms BIGINT
);

CREATE INDEX rule_profiles_owner_updated_idx ON rule_profiles (owner_user_id, updated_at_epoch_ms);

CREATE TABLE tournaments (
    id TEXT PRIMARY KEY,
    owner_user_id UUID NOT NULL REFERENCES users (id),
    name TEXT NOT NULL,
    style TEXT NOT NULL,
    stage TEXT NOT NULL,
    rules_json JSONB NOT NULL,
    points_for_win INTEGER NOT NULL,
    points_for_tie INTEGER NOT NULL,
    points_for_no_result INTEGER NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    created_at_epoch_ms BIGINT NOT NULL,
    updated_at_epoch_ms BIGINT NOT NULL,
    deleted_at_epoch_ms BIGINT
);

CREATE INDEX tournaments_owner_updated_idx ON tournaments (owner_user_id, updated_at_epoch_ms);

CREATE TABLE tournament_teams (
    tournament_id TEXT NOT NULL REFERENCES tournaments (id),
    team_id TEXT NOT NULL REFERENCES teams (id),
    PRIMARY KEY (tournament_id, team_id)
);

CREATE TABLE fixtures (
    id TEXT PRIMARY KEY,
    tournament_id TEXT NOT NULL REFERENCES tournaments (id),
    round TEXT NOT NULL,
    team_a_id TEXT NOT NULL,
    team_b_id TEXT NOT NULL,
    match_id TEXT
);

CREATE INDEX fixtures_tournament_idx ON fixtures (tournament_id);

CREATE TABLE matches (
    id TEXT PRIMARY KEY,
    owner_user_id UUID NOT NULL REFERENCES users (id),
    style TEXT NOT NULL,
    title TEXT NOT NULL,
    team_a_json JSONB NOT NULL,
    team_b_json JSONB NOT NULL,
    rules_json JSONB NOT NULL,
    rule_profile_id TEXT,
    tournament_id TEXT,
    fixture_id TEXT,
    venue TEXT NOT NULL DEFAULT '',
    scheduled_at_epoch_ms BIGINT,
    toss_json JSONB,
    batting_first_team_id TEXT,
    opening_json JSONB,
    status TEXT NOT NULL DEFAULT 'UPCOMING',
    cancelled BOOLEAN NOT NULL DEFAULT FALSE,
    version INTEGER NOT NULL DEFAULT 1,
    created_at_epoch_ms BIGINT NOT NULL,
    updated_at_epoch_ms BIGINT NOT NULL,
    deleted_at_epoch_ms BIGINT
);

CREATE INDEX matches_owner_updated_idx ON matches (owner_user_id, updated_at_epoch_ms);
CREATE INDEX matches_tournament_idx ON matches (tournament_id);

CREATE TABLE match_events (
    match_id TEXT NOT NULL REFERENCES matches (id),
    id TEXT NOT NULL,
    sequence INTEGER NOT NULL,
    event_type TEXT NOT NULL,
    payload JSONB NOT NULL,
    created_at_epoch_ms BIGINT NOT NULL,
    PRIMARY KEY (match_id, id),
    UNIQUE (match_id, sequence)
);

CREATE TABLE memberships (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    resource_type TEXT NOT NULL CHECK (resource_type IN ('TEAM', 'MATCH', 'TOURNAMENT')),
    resource_id TEXT NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('OWNER', 'EDITOR', 'VIEWER')),
    created_at_epoch_ms BIGINT NOT NULL,
    UNIQUE (user_id, resource_type, resource_id)
);

CREATE INDEX memberships_resource_idx ON memberships (resource_type, resource_id);
