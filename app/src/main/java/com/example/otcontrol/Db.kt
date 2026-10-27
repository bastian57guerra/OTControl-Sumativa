package com.example.otcontrol

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Reading(
    val ts: Long, val temp: Float, val rpm: Int, val dist: Float,
    val zone: String, val cooling: Boolean, val stopped: Boolean
)

data class EventRow(val ts: Long, val type: String, val detail: String)

data class Stats(val count: Int, val maxTemp: Float, val minDist: Float, val stops: Int)

/**
 * Base de datos local SQLite (sin internet):
 *  - users: usuarios con hash y sal
 *  - readings: lecturas de los sensores
 *  - events: alertas, comandos y paradas
 * Todas las consultas usan parámetros (?) para evitar inyección SQL.
 */
class Db private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "otcontrol.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE users (username TEXT PRIMARY KEY, salt TEXT NOT NULL, hash TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE readings (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, " +
                "temp REAL, rpm INTEGER, dist REAL, zone TEXT, cooling INTEGER, stopped INTEGER)"
        )
        db.execSQL(
            "CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, " +
                "type TEXT NOT NULL, detail TEXT)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---------------- usuarios

    fun userCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM users", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    fun addUser(username: String, salt: String, hash: String) {
        val v = ContentValues().apply {
            put("username", username)
            put("salt", salt)
            put("hash", hash)
        }
        writableDatabase.insertWithOnConflict("users", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Devuelve (sal, hash) o null si el usuario no existe. */
    fun getUser(username: String): Pair<String, String>? =
        readableDatabase.rawQuery("SELECT salt, hash FROM users WHERE username = ?", arrayOf(username)).use {
            if (it.moveToFirst()) it.getString(0) to it.getString(1) else null
        }

    // ---------------- lecturas y eventos

    fun saveReading(t: Telemetry) {
        val v = ContentValues().apply {
            put("ts", System.currentTimeMillis())
            put("temp", t.tempC)
            put("rpm", t.rpm)
            put("dist", t.distM)
            put("zone", Zone.fromDistance(t.distM).name)
            put("cooling", if (t.cooling) 1 else 0)
            put("stopped", if (t.stopped) 1 else 0)
        }
        writableDatabase.insert("readings", null, v)
        // Solo se guardan las últimas 2000 lecturas
        writableDatabase.execSQL(
            "DELETE FROM readings WHERE id <= (SELECT MAX(id) FROM readings) - 2000"
        )
    }

    fun saveEvent(type: String, detail: String) {
        val v = ContentValues().apply {
            put("ts", System.currentTimeMillis())
            put("type", type)
            put("detail", detail)
        }
        writableDatabase.insert("events", null, v)
    }

    fun lastReadings(limit: Int): List<Reading> =
        readableDatabase.rawQuery(
            "SELECT ts, temp, rpm, dist, zone, cooling, stopped FROM readings ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use {
            val list = ArrayList<Reading>()
            while (it.moveToNext()) {
                list.add(
                    Reading(
                        it.getLong(0), it.getFloat(1), it.getInt(2), it.getFloat(3),
                        it.getString(4), it.getInt(5) == 1, it.getInt(6) == 1
                    )
                )
            }
            list
        }

    fun lastEvents(limit: Int): List<EventRow> =
        readableDatabase.rawQuery(
            "SELECT ts, type, detail FROM events ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use {
            val list = ArrayList<EventRow>()
            while (it.moveToNext()) list.add(EventRow(it.getLong(0), it.getString(1), it.getString(2) ?: ""))
            list
        }

    fun stats(): Stats {
        var count = 0
        var maxTemp = 0f
        var minDist = 0f
        readableDatabase.rawQuery("SELECT COUNT(*), MAX(temp), MIN(dist) FROM readings", null).use {
            if (it.moveToFirst()) {
                count = it.getInt(0)
                maxTemp = it.getFloat(1)
                minDist = it.getFloat(2)
            }
        }
        val stops = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM events WHERE type = 'PARADA'", null
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        return Stats(count, maxTemp, minDist, stops)
    }

    fun clearHistory() {
        writableDatabase.execSQL("DELETE FROM readings")
        writableDatabase.execSQL("DELETE FROM events")
    }

    companion object {
        @Volatile
        private var inst: Db? = null

        fun init(context: Context) {
            if (inst == null) inst = Db(context)
        }

        val instance: Db get() = inst ?: error("Db no inicializada")
    }
}
