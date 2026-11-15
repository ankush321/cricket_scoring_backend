package com.example.cricketscoring.backend.auth

import com.example.cricketscoring.backend.Users
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

data class UserRecord(
    val id: UUID,
    val email: String?,
    val phoneE164: String?,
    val displayName: String,
    val deleted: Boolean = false,
)

/** `users` queries. Each function joins the caller's transaction when there is one. */
class UserRepository {
    fun findById(id: UUID): UserRecord? = transaction {
        Users.selectAll().where { Users.id eq id.toKotlinUuid() }.firstOrNull()?.toUser()
    }

    fun findByPrimaryEmail(email: String): UserRecord? = transaction {
        Users.selectAll().where { Users.primaryEmail eq email }.firstOrNull()?.toUser()
    }

    fun findByPrimaryPhone(phoneE164: String): UserRecord? = transaction {
        Users.selectAll().where { Users.primaryPhoneE164 eq phoneE164 }.firstOrNull()?.toUser()
    }

    /** Locks the user row until the surrounding transaction ends (serialises unlinks for one user). */
    fun lockForUpdate(id: UUID): UserRecord? = transaction {
        Users.selectAll().where { Users.id eq id.toKotlinUuid() }.forUpdate().firstOrNull()?.toUser()
    }

    fun insert(id: UUID, email: String?, phoneE164: String?, displayName: String, nowEpochMs: Long): UserRecord {
        transaction {
            Users.insert {
                it[Users.id] = id.toKotlinUuid()
                it[primaryEmail] = email
                it[primaryPhoneE164] = phoneE164
                it[Users.displayName] = displayName
                it[createdAtEpochMs] = nowEpochMs
                it[updatedAtEpochMs] = nowEpochMs
            }
        }
        return UserRecord(id, email, phoneE164, displayName)
    }

    /** Sets the contact email only if the user has none and no other user owns it. */
    fun setPrimaryEmailIfFree(id: UUID, email: String, nowEpochMs: Long) = setIfFree("primary_email", id, email, nowEpochMs)

    /** Sets the contact phone only if the user has none and no other user owns it. */
    fun setPrimaryPhoneIfFree(id: UUID, phoneE164: String, nowEpochMs: Long) = setIfFree("primary_phone_e164", id, phoneE164, nowEpochMs)

    /** [column] is one of two constants above, never user input. */
    private fun setIfFree(column: String, id: UUID, value: String, nowEpochMs: Long) {
        transaction {
            exec(
                """
                UPDATE users SET $column = ?, updated_at_epoch_ms = ?
                 WHERE id = CAST(? AS uuid) AND $column IS NULL
                   AND NOT EXISTS (SELECT 1 FROM users WHERE $column = ?)
                """.trimIndent(),
                listOf(
                    TextColumnType() to value,
                    LongColumnType() to nowEpochMs,
                    TextColumnType() to id.toString(),
                    TextColumnType() to value,
                ),
            )
        }
    }

    fun clearPrimaryEmail(id: UUID, email: String, nowEpochMs: Long) {
        transaction {
            Users.update({ (Users.id eq id.toKotlinUuid()) and (Users.primaryEmail eq email) }) {
                it[primaryEmail] = null
                it[updatedAtEpochMs] = nowEpochMs
            }
        }
    }

    fun clearPrimaryPhone(id: UUID, phoneE164: String, nowEpochMs: Long) {
        transaction {
            Users.update({ (Users.id eq id.toKotlinUuid()) and (Users.primaryPhoneE164 eq phoneE164) }) {
                it[primaryPhoneE164] = null
                it[updatedAtEpochMs] = nowEpochMs
            }
        }
    }

    /** Turns the user into a tombstone: no contact data, generic name, status DELETED. */
    fun markDeleted(id: UUID, nowEpochMs: Long) {
        transaction {
            Users.update({ Users.id eq id.toKotlinUuid() }) {
                it[primaryEmail] = null
                it[primaryPhoneE164] = null
                it[displayName] = DELETED_NAME
                it[status] = "DELETED"
                it[deletedAtEpochMs] = nowEpochMs
                it[updatedAtEpochMs] = nowEpochMs
            }
        }
    }

    /** Replaces an auto-generated name ("User 1234") with a real one from a provider; leaves chosen names alone. */
    fun replaceGeneratedDisplayName(id: UUID, displayName: String, nowEpochMs: Long) {
        transaction {
            val current = Users.selectAll().where { Users.id eq id.toKotlinUuid() }.firstOrNull()?.get(Users.displayName)
            if (current != null && GENERATED_NAME.matches(current)) {
                Users.update({ Users.id eq id.toKotlinUuid() }) {
                    it[Users.displayName] = displayName
                    it[updatedAtEpochMs] = nowEpochMs
                }
            }
        }
    }

    private fun ResultRow.toUser(): UserRecord = UserRecord(
        id = this[Users.id].toJavaUuid(),
        email = this[Users.primaryEmail],
        phoneE164 = this[Users.primaryPhoneE164],
        displayName = this[Users.displayName],
        deleted = this[Users.status] == "DELETED",
    )

    companion object {
        val GENERATED_NAME = Regex("^User \\d{4}$")
        const val DELETED_NAME = "Deleted user"

        fun generatedName(phoneE164: String) = "User ${phoneE164.takeLast(4)}"
    }
}
