package ir.vmessenger.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "location_share",
    indices = [Index("contactId")],
)
data class LocationShareEntity(
    @PrimaryKey val shareId: String,
    val contactId: String,
    val direction: MessageDirection,
    val active: Boolean,
    val startedAtUnixMs: Long,
    val endedAtUnixMs: Long?,
)

@Entity(
    tableName = "location_sample",
    foreignKeys = [
        ForeignKey(
            entity = LocationShareEntity::class,
            parentColumns = ["shareId"],
            childColumns = ["shareId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("shareId"), Index("sampledAtUnixMs")],
)
data class LocationSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val shareId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
    val speedMps: Float?,
    val headingDeg: Float?,
    val batteryPct: Int?,
    val sampledAtUnixMs: Long,
)
