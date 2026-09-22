package ir.vmessenger.core.database.converter

import androidx.room.TypeConverter
import ir.vmessenger.core.database.entity.ActivityKind
import ir.vmessenger.core.database.entity.ContactRelationshipStatus
import ir.vmessenger.core.database.entity.ContactRequestStatus
import ir.vmessenger.core.database.entity.DeliveryStatus
import ir.vmessenger.core.database.entity.GroupMemberRole
import ir.vmessenger.core.database.entity.MessageContentType
import ir.vmessenger.core.database.entity.MessageDirection
import ir.vmessenger.core.database.entity.MessageRevisionKind

@Suppress("TooManyFunctions") // two converter methods per persisted enum
class EnumConverters {
    @TypeConverter
    fun fromActivityKind(value: ActivityKind): String = value.name

    @TypeConverter
    fun toActivityKind(value: String): ActivityKind = ActivityKind.valueOf(value)

    @TypeConverter
    fun fromDirection(value: MessageDirection): String = value.name

    @TypeConverter
    fun toDirection(value: String): MessageDirection = MessageDirection.valueOf(value)

    @TypeConverter
    fun fromContentType(value: MessageContentType): String = value.name

    @TypeConverter
    fun toContentType(value: String): MessageContentType = MessageContentType.valueOf(value)

    @TypeConverter
    fun fromDeliveryStatus(value: DeliveryStatus): String = value.name

    @TypeConverter
    fun toDeliveryStatus(value: String): DeliveryStatus = DeliveryStatus.valueOf(value)

    @TypeConverter
    fun fromRelationshipStatus(value: ContactRelationshipStatus): String = value.name

    @TypeConverter
    fun toRelationshipStatus(value: String): ContactRelationshipStatus = ContactRelationshipStatus.valueOf(value)

    @TypeConverter
    fun fromGroupMemberRole(value: GroupMemberRole): String = value.name

    @TypeConverter
    fun toGroupMemberRole(value: String): GroupMemberRole = GroupMemberRole.valueOf(value)

    @TypeConverter
    fun fromRevisionKind(value: MessageRevisionKind): String = value.name

    @TypeConverter
    fun toRevisionKind(value: String): MessageRevisionKind = MessageRevisionKind.valueOf(value)

    @TypeConverter
    fun fromContactRequestStatus(value: ContactRequestStatus): String = value.name

    @TypeConverter
    fun toContactRequestStatus(value: String): ContactRequestStatus = ContactRequestStatus.valueOf(value)
}
