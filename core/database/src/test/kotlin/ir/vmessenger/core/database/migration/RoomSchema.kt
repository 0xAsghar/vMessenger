package ir.vmessenger.core.database.migration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** One column as Room declared it. */
internal data class RoomColumn(val name: String, val affinity: String, val notNull: Boolean)

/**
 * The schema Room exported for [version] (`schemas/…/<version>.json`): what a fresh install of the
 * release that shipped it created, table for table and index for index.
 */
internal class RoomSchema(version: Int) {
    private val database: JsonObject = Json.parseToJsonElement(
        File("schemas/ir.vmessenger.core.database.VMessengerDatabase/$version.json").readText(),
    ).jsonObject.getValue("database").jsonObject

    private val entities = database.getValue("entities").jsonArray.map { it.jsonObject }

    /** Every table and its columns, in declaration order. */
    val tables: Map<String, List<RoomColumn>> = entities.associate { entity ->
        entity.text("tableName") to entity.getValue("fields").jsonArray.map { field ->
            val column = field.jsonObject
            RoomColumn(
                name = column.text("columnName"),
                affinity = column.text("affinity"),
                notNull = column.getValue("notNull").jsonPrimitive.boolean,
            )
        }
    }

    /** Creates the schema the way Room did on first open: tables, their indices, then Room's own table. */
    fun create(target: JdbcSupportDatabase) {
        entities.forEach { entity ->
            val table = entity.text("tableName")
            target.exec(entity.text("createSql").replace(TABLE_NAME, table))
            entity["indices"]?.jsonArray?.forEach { index ->
                target.exec(index.jsonObject.text("createSql").replace(TABLE_NAME, table))
            }
        }
        database["setupQueries"]?.jsonArray?.forEach { target.exec(it.jsonPrimitive.content) }
    }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private companion object {
        const val TABLE_NAME = "\${TABLE_NAME}"
    }
}
