package com.waz.zclient.storage.db.users.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.waz.zclient.storage.db.assetsv1.AssetsV1Entity
import com.waz.zclient.storage.db.conversations.CONVERSATIONS_TABLE_NAME

private const val START_VERSION = 126
private const val END_VERSION = 127
private const val USER_PREFERENCE_TABLE_NAME = "KeyValuesTemp"
private const val KEY_VALUES_TABLE_NAME = "KeyValues"

private const val USER_TABLE_NAME = "UserCopy"
private const val USERS_TABLE_NAME = "Users"

//Shared keys
private const val CLIENT_ID_KEY = "id"
private const val CLIENT_LABEL_KEY = "label"
private const val CLIENT_LOCATION_LAT_KEY = "lat"
private const val CLIENT_LOCATION_LONG_KEY = "lon"
private const val CLIENT_ENC_KEY = "encKey"
private const val CLIENT_MAC_KEY = "macKey"
private const val CLIENT_VERIFICATION_KEY = "verification"
private const val CLIENT_MODEL_KEY = "model"
private const val CLIENT_CLASS_KEY = "class"

//New table keys
private const val NEW_CLIENT_TABLE_NAME = "client"
private const val NEW_CLIENT_LOCATION_NAME_KEY = "locationName"
private const val NEW_CLIENT_TIME_KEY = "time"
private const val NEW_CLIENT_TYPE_KEY = "type"

val USER_DATABASE_MIGRATION_126_TO_127 = object : Migration(START_VERSION, END_VERSION) {
    override fun migrate(database: SupportSQLiteDatabase) {
        migrateClientTable(database)
        migrateKeyValuesTable(database)
        migrateUserTable(database)
        migrateAssetsV1Table(database)
        migrateConversationsTable(database)
    }

    //TODO still needs determining what to do with this one.
    private fun migrateClientTable(database: SupportSQLiteDatabase) {
        database.execSQL("""CREATE TABLE '$NEW_CLIENT_TABLE_NAME' (
                |'$CLIENT_ID_KEY' TEXT NOT NULL, 
                |'$NEW_CLIENT_TIME_KEY' TEXT NOT NULL,  
                |'$CLIENT_LABEL_KEY' TEXT NOT NULL, 
                |'$NEW_CLIENT_TYPE_KEY' TEXT NOT NULL, 
                |'$CLIENT_CLASS_KEY' TEXT NOT NULL ,
                |'$CLIENT_MODEL_KEY' TEXT NOT NULL,  
                |'$CLIENT_LOCATION_LAT_KEY' REAL NOT NULL,  
                |'$CLIENT_LOCATION_LONG_KEY' REAL NOT NULL, 
                |'$NEW_CLIENT_LOCATION_NAME_KEY' TEXT,  
                |'$CLIENT_VERIFICATION_KEY' TEXT NOT NULL,  
                |'$CLIENT_ENC_KEY' TEXT NOT NULL,  
                |'$CLIENT_MAC_KEY' TEXT NOT NULL,  
                |PRIMARY KEY('$CLIENT_ID_KEY')
                |)""".trimMargin())
    }

    private fun migrateKeyValuesTable(database: SupportSQLiteDatabase) {
        val createTempKeyValues = """
                CREATE TABLE IF NOT EXISTS `$USER_PREFERENCE_TABLE_NAME` (
                `key` TEXT PRIMARY KEY NOT NULL ,
                `value` TEXT)
                """.trimIndent()
        val copyAllValues = "INSERT INTO $USER_PREFERENCE_TABLE_NAME SELECT * FROM $KEY_VALUES_TABLE_NAME"
        val dropOldValuesTable = "DROP TABLE $KEY_VALUES_TABLE_NAME"
        val renameOldValuesTable = "ALTER TABLE $USER_PREFERENCE_TABLE_NAME RENAME TO $KEY_VALUES_TABLE_NAME"

        with(database) {
            execSQL(createTempKeyValues)
            execSQL(copyAllValues)
            execSQL(dropOldValuesTable)
            execSQL(renameOldValuesTable)
        }
    }

    private fun migrateUserTable(database: SupportSQLiteDatabase) {
        val createTempUserTable = """
              | CREATE TABLE $USER_TABLE_NAME (
              | _id TEXT PRIMARY KEY NOT NULL,
              | teamId TEXT, name TEXT NOT NULL, email TEXT, phone TEXT, tracking_id TEXT,
              | picture TEXT, accent INTEGER, skey TEXT, connection TEXT, conn_timestamp INTEGER,
              | conn_msg TEXT, conversation TEXT, relation TEXT, timestamp INTEGER,
              | verified TEXT, deleted INTEGER NOT NULL, availability INTEGER,
              | handle TEXT, provider_id TEXT, integration_id TEXT, expires_at INTEGER,
              | managed_by TEXT, self_permissions INTEGER, copy_permissions INTEGER, created_by TEXT
              | )""".trimMargin()

        val copyUserTable = """
              |INSERT INTO $USER_TABLE_NAME (
              | _id,
              | teamId, name, email, phone, tracking_id,
              | picture, accent, skey, connection, conn_timestamp,
              | conn_msg, conversation, relation, timestamp,
              | verified, deleted, availability,
              | handle, provider_id, integration_id, expires_at,
              | managed_by, self_permissions, copy_permissions, created_by
              | )
              | SELECT
              | _id,
              | teamId, name, email, phone, tracking_id,
              | picture, accent, skey, connection, conn_timestamp,
              | conn_msg, conversation, relation, timestamp,
              | verified, deleted, availability,
              | handle, provider_id, integration_id, expires_at,
              | managed_by, self_permissions, copy_permissions, created_by
              | FROM $USERS_TABLE_NAME""".trimMargin()

        val dropOldTable = "DROP TABLE $USERS_TABLE_NAME"
        val renameTableBack = "ALTER TABLE $USER_TABLE_NAME RENAME TO $USERS_TABLE_NAME"
        with(database) {
            execSQL(createTempUserTable)
            execSQL(copyUserTable)
            execSQL(dropOldTable)
            execSQL(renameTableBack)
        }
    }

    private fun migrateAssetsV1Table(database: SupportSQLiteDatabase) {
        val tempTableName = "AssetsTemp"
        val createTempTable = """CREATE TABLE `$tempTableName` (
            `_id` TEXT NOT NULL, `asset_type` TEXT, `data` TEXT, PRIMARY KEY(`_id`))
            """.trimIndent()
        val copyAllValues = "INSERT INTO $tempTableName SELECT * FROM ${AssetsV1Entity.TABLE_NAME}"
        val dropOldTable = "DROP TABLE ${AssetsV1Entity.TABLE_NAME}"
        val renameOldTable = "ALTER TABLE $tempTableName RENAME TO ${AssetsV1Entity.TABLE_NAME}"
        with(database) {
            execSQL(createTempTable)
            execSQL(copyAllValues)
            execSQL(dropOldTable)
            execSQL(renameOldTable)
        }
    }

    private fun migrateConversationsTable(database: SupportSQLiteDatabase) {
        val tempTableName = "ConversationsTemp"
        val createTempTable = """
            | CREATE TABLE $tempTableName (_id TEXT PRIMARY KEY NOT NULL, remote_id TEXT NOT NULL, name TEXT ,
            | creator TEXT  NOT NULL, conv_type INTEGER  NOT NULL, team TEXT , is_managed INTEGER ,
            | last_event_time INTEGER  NOT NULL, is_active INTEGER  NOT NULL, last_read INTEGER  NOT NULL,
            | muted_status INTEGER  NOT NULL, mute_time INTEGER  NOT NULL, archived INTEGER  NOT NULL,
            | archive_time INTEGER  NOT NULL, cleared INTEGER , generated_name TEXT NOT NULL, search_key TEXT ,
            | unread_count INTEGER  NOT NULL, unsent_count INTEGER  NOT NULL, hidden INTEGER  NOT NULL,
            | missed_call TEXT , incoming_knock TEXT , verified TEXT  NOT NULL, ephemeral INTEGER ,
            | global_ephemeral INTEGER , unread_call_count INTEGER  NOT NULL, unread_ping_count INTEGER  NOT NULL,
            | access TEXT  NOT NULL, access_role TEXT  NOT NULL, link TEXT , unread_mentions_count INTEGER  NOT NULL,
            | unread_quote_count INTEGER  NOT NULL, receipt_mode INTEGER 
            | )""".trimMargin()
        val copyAllValues = "INSERT INTO $tempTableName SELECT * FROM $CONVERSATIONS_TABLE_NAME"
        val dropOldTable = "DROP TABLE $CONVERSATIONS_TABLE_NAME"
        val renameOldTable = "ALTER TABLE $tempTableName RENAME TO $CONVERSATIONS_TABLE_NAME"
        with(database) {
            execSQL(createTempTable)
            execSQL(copyAllValues)
            execSQL(dropOldTable)
            execSQL(renameOldTable)
        }
    }
}
