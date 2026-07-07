package ir.vmessenger.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "location_access",
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["id"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class LocationAccessEntity(
    @PrimaryKey val contactId: String,
    val canSeeMyLocation: Boolean,
    val updatedAtUnixMs: Long,
)
