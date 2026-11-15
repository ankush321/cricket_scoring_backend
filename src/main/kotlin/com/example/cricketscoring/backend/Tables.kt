package com.example.cricketscoring.backend

import org.jetbrains.exposed.v1.core.Table

/**
 * Maps the device document model.
 * Nested rules, match squads, and ball events stay JSON so a later sync can
 * store the same ids and payloads the app already writes.
 */
object Users : Table("users") {
    val id = uuid("id")
    /** Verified contact email (from a Google/Apple identity), unique. */
    val primaryEmail = text("primary_email").nullable()
    /** Verified contact phone (from a WhatsApp identity), E.164, unique. */
    val primaryPhoneE164 = text("primary_phone_e164").nullable()
    val displayName = text("display_name")
    /** ACTIVE or DELETED. A deleted user is a scrubbed tombstone kept for foreign keys. */
    val status = text("status")
    val deletedAtEpochMs = long("deleted_at_epoch_ms").nullable()
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")
    override val primaryKey = PrimaryKey(id)
}

object AppleTokens : Table("apple_tokens") {
    val identityId = uuid("identity_id")
    val clientId = text("client_id")
    val refreshTokenCipher = binary("refresh_token_cipher")
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")
    override val primaryKey = PrimaryKey(identityId)
}

object UserIdentities : Table("user_identities") {
    val id = uuid("id")
    val userId = uuid("user_id")
    val provider = text("provider")
    val providerSubject = text("provider_subject")
    val email = text("email").nullable()
    val emailVerified = bool("email_verified")
    val isPrivateRelay = bool("is_private_relay")
    val phoneE164 = text("phone_e164").nullable()
    val displayName = text("display_name").nullable()
    val avatarUrl = text("avatar_url").nullable()
    val createdAtEpochMs = long("created_at_epoch_ms")
    val lastUsedAtEpochMs = long("last_used_at_epoch_ms")
    override val primaryKey = PrimaryKey(id)
}

object OtpChallenges : Table("otp_challenges") {
    val id = uuid("id")
    val phoneE164 = text("phone_e164")
    val purpose = text("purpose")
    val channel = text("channel")
    val codeHash = binary("code_hash")
    val attemptCount = integer("attempt_count")
    val maxAttempts = integer("max_attempts")
    val expiresAtEpochMs = long("expires_at_epoch_ms")
    val consumedAtEpochMs = long("consumed_at_epoch_ms").nullable()
    val consumedReason = text("consumed_reason").nullable()
    val deliveryChannel = text("delivery_channel").nullable()
    val providerMessageId = text("provider_message_id").nullable()
    val deliveryStatus = text("delivery_status").nullable()
    val createdAtEpochMs = long("created_at_epoch_ms")
    override val primaryKey = PrimaryKey(id)
}

object Players : Table("players") {
    val id = text("id")
    val ownerUserId = uuid("owner_user_id")
    val name = text("name")
    val role = text("role")
    val photoUri = text("photo_uri").nullable()
    val version = integer("version")
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")
    val deletedAtEpochMs = long("deleted_at_epoch_ms").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Teams : Table("teams") {
    val id = text("id")
    val ownerUserId = uuid("owner_user_id")
    val name = text("name")
    val shortName = text("short_name")
    val color = integer("color")
    val captainPlayerId = text("captain_player_id").nullable()
    val wicketkeeperPlayerId = text("wicketkeeper_player_id").nullable()
    val archived = bool("archived")
    val version = integer("version")
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")
    val deletedAtEpochMs = long("deleted_at_epoch_ms").nullable()
    override val primaryKey = PrimaryKey(id)
}

object TeamPlayers : Table("team_players") {
    val teamId = text("team_id")
    val playerId = text("player_id")
    val position = integer("position")
    override val primaryKey = PrimaryKey(teamId, playerId)
}

object RuleProfiles : Table("rule_profiles") {
    val id = text("id")
    val ownerUserId = uuid("owner_user_id")
    val name = text("name")
    val style = text("style")
    val ruleSource = text("source")
    val confirmedByOrganizer = bool("confirmed_by_organizer")
    val rulesJson = jsonb("rules_json")
    val version = integer("version")
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")
    val deletedAtEpochMs = long("deleted_at_epoch_ms").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Tournaments : Table("tournaments") {
    val id = text("id")
    val ownerUserId = uuid("owner_user_id")
    val name = text("name")
    val style = text("style")
    val stage = text("stage")
    val rulesJson = jsonb("rules_json")
    val pointsForWin = integer("points_for_win")
    val pointsForTie = integer("points_for_tie")
    val pointsForNoResult = integer("points_for_no_result")
    val version = integer("version")
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")
    val deletedAtEpochMs = long("deleted_at_epoch_ms").nullable()
    override val primaryKey = PrimaryKey(id)
}

object TournamentTeams : Table("tournament_teams") {
    val tournamentId = text("tournament_id")
    val teamId = text("team_id")
    override val primaryKey = PrimaryKey(tournamentId, teamId)
}

object Fixtures : Table("fixtures") {
    val id = text("id")
    val tournamentId = text("tournament_id")
    val round = text("round")
    val teamAId = text("team_a_id")
    val teamBId = text("team_b_id")
    val matchId = text("match_id").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Matches : Table("matches") {
    val id = text("id")
    val ownerUserId = uuid("owner_user_id")
    val style = text("style")
    val title = text("title")
    val teamAJson = jsonb("team_a_json")
    val teamBJson = jsonb("team_b_json")
    val rulesJson = jsonb("rules_json")
    val ruleProfileId = text("rule_profile_id").nullable()
    val tournamentId = text("tournament_id").nullable()
    val fixtureId = text("fixture_id").nullable()
    val venue = text("venue")
    val scheduledAtEpochMs = long("scheduled_at_epoch_ms").nullable()
    val tossJson = jsonb("toss_json").nullable()
    val battingFirstTeamId = text("batting_first_team_id").nullable()
    val openingJson = jsonb("opening_json").nullable()
    val status = text("status")
    val cancelled = bool("cancelled")
    val version = integer("version")
    val createdAtEpochMs = long("created_at_epoch_ms")
    val updatedAtEpochMs = long("updated_at_epoch_ms")
    val deletedAtEpochMs = long("deleted_at_epoch_ms").nullable()
    override val primaryKey = PrimaryKey(id)
}

object MatchEvents : Table("match_events") {
    val matchId = text("match_id")
    val id = text("id")
    val sequence = integer("sequence")
    val eventType = text("event_type")
    val payload = jsonb("payload")
    val createdAtEpochMs = long("created_at_epoch_ms")
    override val primaryKey = PrimaryKey(matchId, id)
}

object Memberships : Table("memberships") {
    val id = uuid("id")
    val userId = uuid("user_id")
    val resourceType = text("resource_type")
    val resourceId = text("resource_id")
    val role = text("role")
    val createdAtEpochMs = long("created_at_epoch_ms")
    override val primaryKey = PrimaryKey(id)
}

object Sessions : Table("sessions") {
    val id = uuid("id")
    val userId = uuid("user_id")
    val authMethod = text("auth_method")
    val deviceName = text("device_name").nullable()
    val userAgent = text("user_agent").nullable()
    val ipAtCreation = text("ip_at_creation").nullable()
    val createdAtEpochMs = long("created_at_epoch_ms")
    val lastUsedAtEpochMs = long("last_used_at_epoch_ms")
    val expiresAtEpochMs = long("expires_at_epoch_ms")
    val revokedAtEpochMs = long("revoked_at_epoch_ms").nullable()
    val revokedReason = text("revoked_reason").nullable()
    val isBetaFlow = bool("is_beta_flow")
    override val primaryKey = PrimaryKey(id)
}

object RefreshTokens : Table("refresh_tokens") {
    val id = uuid("id")
    val sessionId = uuid("session_id")
    val tokenHash = binary("token_hash")
    val parentId = uuid("parent_id").nullable()
    val createdAtEpochMs = long("created_at_epoch_ms")
    val expiresAtEpochMs = long("expires_at_epoch_ms")
    val usedAtEpochMs = long("used_at_epoch_ms").nullable()
    override val primaryKey = PrimaryKey(id)
}

object RateLimitBuckets : Table("rate_limit_buckets") {
    val bucketKey = text("bucket_key")
    val windowStartEpochMs = long("window_start_epoch_ms")
    val count = integer("count")
    override val primaryKey = PrimaryKey(bucketKey, windowStartEpochMs)
}

object AuthEvents : Table("auth_events") {
    val id = uuid("id")
    val eventType = text("event_type")
    val provider = text("provider").nullable()
    val outcome = text("outcome")
    val errorCode = text("error_code").nullable()
    val userId = uuid("user_id").nullable()
    val isBetaFlow = bool("is_beta_flow")
    val ip = text("ip").nullable()
    val phoneHash = text("phone_hash").nullable()
    val metadata = jsonb("metadata").nullable()
    val createdAtEpochMs = long("created_at_epoch_ms")
    override val primaryKey = PrimaryKey(id)
}
