package com.blaze.assistant.agent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * One mind (v0.18.0), mirroring Blaze Desktop's internal/mind (docs/plans/plan-one-mind.md in Blaze-dekstop).
 *
 *  - One continuous conversation ([MAIN]); projects keep their own threads.
 *  - journal: every turn of every conversation (old chats imported once, idempotently), with FTS, queued for review.
 *  - memories: the source of truth for what Blaze knows (profile, preference, person, project, decision, fact), with FTS.
 *    [save] dedupes: same kind + subject and word overlap >= 0.5 rewrites the line (newest wins), identical text is a no-op.
 *  - The prompt carries only [MIND_RULE], the pinned core and the subject index ([promptBlock]); everything else is looked up.
 *  - Context ladder ([rung]): recent turns, then the rolling summary, then lookup (core + index + last [LOOKUP_TAIL] messages).
 *
 * Storage is filesDir/mind.db with Android's built-in SQLite and FTS4 (like longchat.db; FTS5 isn't on every Android).
 * The pure parts (dedupe, core, index, ladder, extraction parsing) are unit-tested on the JVM.
 */
object Mind {
    const val MAIN = "main"
    val KINDS = listOf("profile", "preference", "person", "project", "decision", "fact")
    const val CORE_MAX = 1600
    const val CORE_PROFILE = 20
    const val CORE_PREFS = 12
    const val INDEX_PER_KIND = 14
    const val LOOKUP_TAIL = 6
    const val CHUNK_CHARS = 12_000
    const val EXTRACT_CHARS = 6_000
    const val SAME = 0.5
    const val INFERRED = "(inferred)"

    // ---------------- shared prompt text (word for word with Desktop) ----------------

    const val MIND_RULE = "ONE MIND\n" +
        "- This is one continuous conversation with the user; there are no separate chats. What you know lives in the memory table and the journal, not in this prompt.\n" +
        "- WHAT YOU KNOW (below) is only the pinned core and an index of subjects. Before answering anything about the user, their people, projects, plans or past conversations, call memory_search (and search_chat for exact earlier words). Never ask the user to repeat something you could look up.\n" +
        "- Save as you go: memory_save for anything lasting (profile, preference, person, project state, decision, commitment). One line each, the user's own conditions kept exactly (\"on weekdays\", \"for work trips\"); write \"(inferred)\" when you inferred it. memory_update rewrites a line that changed; never keep two versions.\n" +
        "- Never save procedure, one-off values (prices, counts, codes) or secrets.\n"

    const val LOOKUP_RULE = "CONTEXT RESET: earlier turns are NOT in this prompt. You don't remember them; look them up. Use memory_search for facts, preferences and decisions, and search_chat / read_turn for exact earlier words, before you answer. Don't guess and don't ask the user to repeat."

    const val EXTRACT_PROMPT = "You maintain the user's memory for Blaze. From the conversation below, list what is worth remembering for months: profile facts (name, school, work, where they live), standing preferences (with every condition the user attached), people in their life and who they are to them, project state, decisions and why, commitments and deadlines.\n" +
        "Skip: procedure, how a tool works, one-off values (prices, counts, order numbers, codes), small talk, secrets and passwords, anything already in EXISTING unless it changed.\n" +
        "Write lines no broader or firmer than the user said. Mark your inferences \"(inferred)\".\n" +
        "Answer ONLY a JSON array, [] when nothing: [{\"kind\":\"profile|preference|person|project|decision|fact\",\"subject\":\"who or what it is about\",\"text\":\"one line\",\"related\":\"other subjects, comma-separated\"}]"

    // ---------------- data ----------------

    data class Mem(val id: Long, val kind: String, val subject: String, val text: String, val related: String = "",
                   val sourceTurn: Long = 0, val created: Long = 0, val updated: Long = 0)

    data class Turn(val id: Long, val conv: String, val turn: Int, val role: String, val content: String, val at: Long = 0)

    data class Hit(val id: Long, val conv: String, val turn: Int, val role: String, val snippet: String)

    data class Item(val kind: String, val subject: String, val text: String, val related: String = "")

    sealed class Decision {
        object Insert : Decision()
        data class Update(val id: Long) : Decision()
        data class Same(val id: Long) : Decision()
    }

    fun normKind(k: String?): String = k.orEmpty().trim().lowercase().let { if (it in KINDS) it else "fact" }

    fun words(s: String): Set<String> = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 && it != "inferred" }.toSet()

    fun jaccard(a: String, b: String): Double {
        val x = words(a); val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return (x intersect y).size.toDouble() / (x union y).size
    }

    private fun squash(s: String) = s.trim().split(Regex("\\s+")).joinToString(" ")

    /** One clean line; "(inferred)" added once when [inferred]. */
    fun clean(text: String, inferred: Boolean = false): String {
        var t = squash(text).removePrefix("- ").removePrefix("* ").trim().removeSuffix(".").trim()
        if (inferred && !t.contains(INFERRED, true)) t = "$t $INFERRED"
        return t.take(600)
    }

    /** What [save] does with a new line, given what is already stored. */
    fun decide(existing: List<Mem>, kind: String, subject: String, text: String): Decision {
        val k = normKind(kind); val s = subject.trim()
        val same = existing.filter { it.kind == k && it.subject.equals(s, true) }
        same.firstOrNull { squash(it.text).equals(squash(text), true) }?.let { return Decision.Same(it.id) }
        // The identical line saved under another subject or kind is still a duplicate.
        existing.firstOrNull { squash(it.text).equals(squash(text), true) }?.let { return Decision.Same(it.id) }
        val best = same.maxByOrNull { jaccard(it.text, text) } ?: return Decision.Insert
        return if (jaccard(best.text, text) >= SAME) Decision.Update(best.id) else Decision.Insert
    }

    /** Pinned core: every profile line (newest [CORE_PROFILE]) then the newest [CORE_PREFS] preferences, under [CORE_MAX] chars. */
    fun core(mems: List<Mem>): String {
        val pick = mems.filter { it.kind == "profile" }.sortedByDescending { it.updated }.take(CORE_PROFILE) +
            mems.filter { it.kind == "preference" }.sortedByDescending { it.updated }.take(CORE_PREFS)
        val sb = StringBuilder()
        for (m in pick) {
            val line = "- [${m.kind}] ${m.subject}: ${m.text}\n"
            if (sb.length + line.length > CORE_MAX) break
            sb.append(line)
        }
        return sb.toString()
    }

    /** Subject index: `person: Gloria (3), Mutale (1)`, [INDEX_PER_KIND] subjects per kind. */
    fun index(mems: List<Mem>): String {
        val sb = StringBuilder()
        for (k in KINDS) {
            val subs = mems.filter { it.kind == k }.groupBy { it.subject.trim().lowercase() }
                .map { (_, l) -> Triple(l.first().subject.trim(), l.size, l.maxOf { it.updated }) }
                .sortedWith(compareByDescending<Triple<String, Int, Long>> { it.second }.thenByDescending { it.third })
            if (subs.isEmpty()) continue
            sb.append("- ").append(k).append(": ")
                .append(subs.take(INDEX_PER_KIND).joinToString(", ") { "${it.first} (${it.second})" })
            if (subs.size > INDEX_PER_KIND) sb.append(", +").append(subs.size - INDEX_PER_KIND).append(" more")
            sb.append('\n')
        }
        return sb.toString()
    }

    /** MIND_RULE + the WHAT YOU KNOW block: the only memory text in any prompt. */
    fun block(mems: List<Mem>): String {
        val c = core(mems); val i = index(mems)
        return "\n" + MIND_RULE + "WHAT YOU KNOW (pinned core; memory_search for the rest)\n" +
            c.ifBlank { "- (nothing pinned yet)\n" } +
            "MEMORY INDEX (subjects you have notes on; look them up)\n" + i.ifBlank { "- (no notes yet)\n" }
    }

    // ---------------- context ladder ----------------

    enum class Rung { RECENT, SUMMARY, LOOKUP }

    /**
     * Which rung of the ladder a turn uses. [history] = tokens of the history in this context window,
     * [budget] = when the summary starts (12k or 60% of the window), [window] = the model's context (0 = unknown, 32k assumed).
     */
    fun rung(history: Int, budget: Int, hasSummary: Boolean, summary: Int, tail: Int, window: Int): Rung {
        if (history <= budget) return Rung.RECENT
        val w = if (window > 0) window else 32_768
        if (hasSummary && summary + tail <= w * 9 / 10) return Rung.SUMMARY
        return Rung.LOOKUP
    }

    /** Where the lookup tail starts: the last [LOOKUP_TAIL] messages, moved forward to a user message (never before [from]). */
    fun lookupStart(h: JSONArray, from: Int = 0): Int {
        val n = h.length()
        var i = maxOf(from, n - LOOKUP_TAIL)
        while (i < n && h.optJSONObject(i)?.optString("role") != "user") i++
        if (i >= n) {
            // No user message in the tail: walk back to the newest one.
            i = n - 1
            while (i > from && h.optJSONObject(i)?.optString("role") != "user") i--
        }
        return i.coerceIn(from.coerceAtMost(n), n)
    }

    // ---------------- extraction ----------------

    fun render(turns: List<Turn>): String = turns.joinToString("\n") { t ->
        var c = t.content
        if (t.role == "tool" && c.length > 600) c = c.take(400) + " … " + c.takeLast(150)
        "[${t.role}] $c"
    }

    /** The first turns of [turns] that fit in [max] characters (always at least one). */
    fun chunk(turns: List<Turn>, max: Int = CHUNK_CHARS): List<Turn> {
        val out = ArrayList<Turn>(); var n = 0
        for (t in turns) {
            val len = minOf(t.content.length, 1200) + 16
            if (out.isNotEmpty() && n + len > max) break
            out += t; n += len
        }
        return out
    }

    fun extractRequest(conversation: String, existing: String): JSONArray = JSONArray()
        .put(JSONObject().put("role", "system").put("content", EXTRACT_PROMPT))
        .put(JSONObject().put("role", "user").put("content", "EXISTING:\n" + existing.ifBlank { "(nothing yet)" } +
            "\n\nCONVERSATION:\n" + conversation.takeLast(CHUNK_CHARS)))

    /** The model's answer → items. Tolerates code fences and text around the array; unusable → empty. */
    fun parseExtract(raw: String): List<Item> {
        val s = raw.substringAfterLast("</think>")
        val a = s.indexOf('['); val b = s.lastIndexOf(']')
        if (a < 0 || b <= a) return emptyList()
        val arr = try { JSONArray(s.substring(a, b + 1)) } catch (_: Exception) { return emptyList() }
        val out = ArrayList<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val text = clean(o.optString("text"))
            val subject = squash(o.optString("subject")).take(60)
            if (text.length < 3 || subject.isEmpty()) continue
            if (Learn.secret(text)) continue
            out += Item(normKind(o.optString("kind")), subject, text, squash(o.optString("related")).take(200))
        }
        return out.take(12)
    }

    /** A kind and subject for a fact saved before one mind (a plain sentence). */
    fun guessKind(fact: String): Pair<String, String> {
        val f = fact.lowercase()
        val person = Regex("\\b(sister|brother|mom|mum|mother|dad|father|wife|husband|friend|boss|son|daughter|girlfriend|boyfriend|cousin|aunt|uncle|grandma|grandpa|partner|teacher|lecturer)\\b")
        return when {
            person.containsMatchIn(f) -> "person" to (Regex("\\b([A-Z][a-z]{2,})\\b").findAll(fact).map { it.value }
                .firstOrNull { !person.matches(it.lowercase()) && it.lowercase() !in setOf("the", "my", "his", "her", "user", "they", "lives", "works", "is") } ?: "People")
            Regex("\\b(prefers?|likes?|loves?|hates?|dislikes?|always|never|don't|doesn't|wants?|favou?rite|rather)\\b").containsMatchIn(f) -> "preference" to "You"
            Regex("\\b(name is|called|lives? in|from|stud(y|ies|ent)|school|university|works? (at|as)|job|born|birthday|age)\\b").containsMatchIn(f) -> "profile" to "You"
            else -> "fact" to "You"
        }
    }

    // ---------------- the database ----------------

    class Db(ctx: Context, name: String? = "mind.db") : SQLiteOpenHelper(ctx, name, null, 1) {
        @Volatile var fts = true

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS journal(id INTEGER PRIMARY KEY AUTOINCREMENT, conv TEXT NOT NULL, turn INTEGER NOT NULL, " +
                "role TEXT NOT NULL, content TEXT NOT NULL, at INTEGER NOT NULL, reviewed INTEGER NOT NULL DEFAULT 0, UNIQUE(conv, turn))")
            db.execSQL("CREATE INDEX IF NOT EXISTS journal_unreviewed ON journal(reviewed, id)")
            db.execSQL("CREATE TABLE IF NOT EXISTS memories(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, subject TEXT NOT NULL, " +
                "text TEXT NOT NULL, related TEXT NOT NULL DEFAULT '', source_turn INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL, " +
                "updated INTEGER NOT NULL, mirror INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE INDEX IF NOT EXISTS memories_subject ON memories(kind, subject)")
            db.execSQL("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            makeFts(db)
        }

        override fun onOpen(db: SQLiteDatabase) {
            super.onOpen(db)
            fts = try { db.rawQuery("SELECT count(*) FROM journal_fts", null).use { true } } catch (_: Exception) { makeFts(db) }
        }

        private fun makeFts(db: SQLiteDatabase): Boolean = try {
            try {
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS journal_fts USING fts4(content, tokenize=porter)")
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts4(subject, text, tokenize=porter)")
            } catch (_: Exception) {
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS journal_fts USING fts4(content)")
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts4(subject, text)")
            }
            true
        } catch (_: Exception) { fts = false; false }

        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) { }

        // ---- meta ----
        fun meta(key: String): String = readableDatabase.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else "" }
        fun setMeta(key: String, v: String) {
            writableDatabase.insertWithOnConflict("meta", null, ContentValues().apply { put("key", key); put("value", v) }, SQLiteDatabase.CONFLICT_REPLACE)
        }

        // ---- journal ----
        private val synced = java.util.concurrent.ConcurrentHashMap<String, Int>()

        private fun maxTurn(conv: String): Int = readableDatabase.rawQuery("SELECT MAX(turn) FROM journal WHERE conv=?", arrayOf(conv)).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) + 1 else 0 }

        /**
         * Adds (or updates, after a retry) the turns of [conv]. Only the last stretch is re-checked each time, so a long
         * main thread costs little. [full] re-checks every turn (the migration). Returns how many rows were added.
         */
        @Synchronized fun journal(conv: String, h: JSONArray, at: Long = System.currentTimeMillis() / 1000, full: Boolean = false): Int {
            val n = h.length(); if (n == 0) return 0
            val known = if (full) 0 else (synced[conv] ?: maxTurn(conv))
            val from = if (full) 0 else (minOf(known, n) - 24).coerceAtLeast(0)
            val db = writableDatabase
            var added = 0
            db.beginTransaction()
            try {
                for (i in from until n) {
                    val m = h.optJSONObject(i) ?: continue
                    val text = LongChat.text(m).trim().take(8000)
                    if (text.isBlank()) continue
                    val role = m.optString("role").ifBlank { "user" }
                    val id = db.insertWithOnConflict("journal", null, ContentValues().apply {
                        put("conv", conv); put("turn", i); put("role", role); put("content", text); put("at", at); put("reviewed", 0) },
                        SQLiteDatabase.CONFLICT_IGNORE)
                    if (id > 0) {
                        added++
                        if (fts) db.insert("journal_fts", null, ContentValues().apply { put("docid", id); put("content", text) })
                    } else {
                        // Already there: a retry may have changed that turn.
                        db.rawQuery("SELECT id, content FROM journal WHERE conv=? AND turn=?", arrayOf(conv, i.toString())).use { c ->
                            if (c.moveToFirst() && c.getString(1) != text) {
                                val old = c.getLong(0)
                                db.update("journal", ContentValues().apply { put("content", text); put("role", role); put("reviewed", 0) }, "id=?", arrayOf(old.toString()))
                                if (fts) {
                                    db.delete("journal_fts", "docid=?", arrayOf(old.toString()))
                                    db.insert("journal_fts", null, ContentValues().apply { put("docid", old); put("content", text) })
                                }
                            }
                        }
                    }
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            synced[conv] = n
            return added
        }

        fun count(where: String = "1=1"): Int = readableDatabase.rawQuery("SELECT count(*) FROM journal WHERE $where", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0 }
        fun unreviewedCount(): Int = count("reviewed=0")

        private fun turnOf(c: android.database.Cursor) = Turn(c.getLong(0), c.getString(1), c.getInt(2), c.getString(3), c.getString(4), c.getLong(5))
        private val turnCols = "id, conv, turn, role, content, at"

        fun unreviewed(limit: Int = 200): List<Turn> = readableDatabase.rawQuery(
            "SELECT $turnCols FROM journal WHERE reviewed=0 ORDER BY id LIMIT ?", arrayOf(limit.toString())).use { c ->
            val out = ArrayList<Turn>(); while (c.moveToNext()) out += turnOf(c); out }

        fun markReviewed(ids: List<Long>) {
            if (ids.isEmpty()) return
            val db = writableDatabase
            db.beginTransaction()
            try { ids.forEach { db.execSQL("UPDATE journal SET reviewed=1 WHERE id=?", arrayOf<Any>(it)) }; db.setTransactionSuccessful() }
            finally { db.endTransaction() }
        }

        fun turn(id: Long): Turn? = readableDatabase.rawQuery("SELECT $turnCols FROM journal WHERE id=?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) turnOf(c) else null }

        fun searchJournal(query: String, conv: String?, limit: Int): List<Hit> {
            val ws = words(query).toList().take(8); if (ws.isEmpty()) return emptyList()
            val rows = ArrayList<Turn>()
            val q = LongChat.ftsQuery(query)
            if (fts && q.isNotEmpty()) try {
                val sql = "SELECT j.id, j.conv, j.turn, j.role, j.content, j.at FROM journal j JOIN journal_fts f ON f.docid=j.id WHERE journal_fts MATCH ?" +
                    (if (conv != null) " AND j.conv=?" else "") + " ORDER BY j.id DESC LIMIT 200"
                readableDatabase.rawQuery(sql, if (conv != null) arrayOf(q, conv) else arrayOf(q)).use { c -> while (c.moveToNext()) rows += turnOf(c) }
            } catch (_: Exception) { rows.clear(); likeJournal(ws, conv, rows) }
            else likeJournal(ws, conv, rows)
            return rows.map { it to ws.count { w -> it.content.contains(w, true) } }
                .sortedWith(compareByDescending<Pair<Turn, Int>> { it.second }.thenByDescending { it.first.id })
                .take(limit).map { (t, _) -> Hit(t.id, t.conv, t.turn, t.role, snippet(t.content, ws)) }
        }

        private fun likeJournal(ws: List<String>, conv: String?, out: MutableList<Turn>) {
            val where = ws.joinToString(" OR ") { "content LIKE ?" }
            val args = ws.map { "%$it%" } + listOfNotNull(conv)
            readableDatabase.rawQuery("SELECT $turnCols FROM journal WHERE ($where)" + (if (conv != null) " AND conv=?" else "") + " ORDER BY id DESC LIMIT 200",
                args.toTypedArray()).use { c -> while (c.moveToNext()) out += turnOf(c) }
        }

        // ---- memories ----
        private fun memOf(c: android.database.Cursor) = Mem(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getLong(5), c.getLong(6), c.getLong(7))
        private val memCols = "id, kind, subject, text, related, source_turn, created, updated"

        fun all(): List<Mem> = readableDatabase.rawQuery("SELECT $memCols FROM memories ORDER BY created, id", null).use { c ->
            val out = ArrayList<Mem>(); while (c.moveToNext()) out += memOf(c); out }

        fun mem(id: Long): Mem? = readableDatabase.rawQuery("SELECT $memCols FROM memories WHERE id=?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) memOf(c) else null }

        private fun ftsPut(db: SQLiteDatabase, id: Long, subject: String, text: String, replace: Boolean) {
            if (!fts) return
            try {
                if (replace) db.delete("memories_fts", "docid=?", arrayOf(id.toString()))
                db.insert("memories_fts", null, ContentValues().apply { put("docid", id); put("subject", subject); put("text", text) })
            } catch (_: Exception) { }
        }

        /** Insert or rewrite through [decide]. Returns (outcome, id); outcome is "saved", "updated" or "same". */
        @Synchronized fun save(kind: String, subject: String, text: String, related: String = "", sourceTurn: Long = 0, now: Long = System.currentTimeMillis()): Pair<String, Long> {
            val k = normKind(kind); val s = squash(subject).ifBlank { "You" }.take(60); val t = clean(text)
            val db = writableDatabase
            return when (val d = decide(all(), k, s, t)) {
                is Decision.Same -> "same" to d.id
                is Decision.Update -> {
                    db.update("memories", ContentValues().apply { put("text", t); put("updated", now); if (related.isNotBlank()) put("related", related)
                        if (sourceTurn > 0) put("source_turn", sourceTurn) }, "id=?", arrayOf(d.id.toString()))
                    ftsPut(db, d.id, s, t, true); "updated" to d.id
                }
                Decision.Insert -> {
                    val id = db.insert("memories", null, ContentValues().apply { put("kind", k); put("subject", s); put("text", t)
                        put("related", related); put("source_turn", sourceTurn); put("created", now); put("updated", now) })
                    ftsPut(db, id, s, t, false); "saved" to id
                }
            }
        }

        @Synchronized fun update(id: Long, text: String, kind: String? = null, subject: String? = null, now: Long = System.currentTimeMillis()): Boolean {
            val old = mem(id) ?: return false
            val t = clean(text).ifBlank { old.text }
            val s = subject?.let { squash(it).take(60) }?.ifBlank { null } ?: old.subject
            val db = writableDatabase
            db.update("memories", ContentValues().apply { put("text", t); put("subject", s); put("updated", now)
                if (!kind.isNullOrBlank()) put("kind", normKind(kind)) }, "id=?", arrayOf(id.toString()))
            ftsPut(db, id, s, t, true)
            return true
        }

        @Synchronized fun delete(id: Long) {
            writableDatabase.delete("memories", "id=?", arrayOf(id.toString()))
            if (fts) try { writableDatabase.delete("memories_fts", "docid=?", arrayOf(id.toString())) } catch (_: Exception) { }
        }

        fun search(query: String, kind: String? = null, limit: Int = 12): List<Mem> {
            val ws = words(query).toList().take(8)
            val k = kind?.trim()?.lowercase()?.takeIf { it in KINDS }
            if (ws.isEmpty()) return all().filter { k == null || it.kind == k }.sortedByDescending { it.updated }.take(limit)
            val rows = LinkedHashMap<Long, Mem>()
            val q = LongChat.ftsQuery(query)
            if (fts && q.isNotEmpty()) try {
                readableDatabase.rawQuery("SELECT m.id, m.kind, m.subject, m.text, m.related, m.source_turn, m.created, m.updated FROM memories m " +
                    "JOIN memories_fts f ON f.docid=m.id WHERE memories_fts MATCH ? LIMIT 200", arrayOf(q)).use { c -> while (c.moveToNext()) memOf(c).let { rows[it.id] = it } }
            } catch (_: Exception) { }
            // Plain substring matches too (FTS missing, or words FTS tokenises differently).
            all().filter { m -> ws.any { w -> (m.subject + " " + m.text + " " + m.related).contains(w, true) } }.forEach { rows[it.id] = it }
            return rows.values.filter { k == null || it.kind == k }
                .map { it to ws.count { w -> (it.subject + " " + it.text + " " + it.related).contains(w, true) } }
                .sortedWith(compareByDescending<Pair<Mem, Int>> { it.second }.thenByDescending { it.first.updated })
                .take(limit).map { it.first }
        }
    }

    fun snippet(s: String, ws: List<String>, n: Int = 220): String {
        val flat = s.replace('\n', ' ')
        val at = ws.map { flat.indexOf(it, ignoreCase = true) }.filter { it >= 0 }.minOrNull() ?: 0
        val from = (at - 60).coerceAtLeast(0)
        return (if (from > 0) "…" else "") + flat.substring(from, minOf(flat.length, from + n)) + (if (from + n < flat.length) "…" else "")
    }

    // ---------------- the app-wide instance ----------------

    @Volatile private var db: Db? = null
    val ready: Boolean get() = db != null
    fun db(): Db? = db

    /** Called after any memory change (the Memory screen refreshes from it). */
    @Volatile var onChange: (() -> Unit)? = null
    private fun changed() { try { onChange?.invoke() } catch (_: Throwable) { } }

    fun init(ctx: Context) {
        if (db != null) return
        db = try { Db(ctx.applicationContext).also { it.writableDatabase } } catch (_: Throwable) { null }
    }

    /** For tests: use this database. */
    fun use(d: Db?) { db = d }

    fun all(): List<Mem> = try { db?.all() ?: emptyList() } catch (_: Exception) { emptyList() }

    fun promptBlock(): String = block(all())

    fun save(kind: String, subject: String, text: String, related: String = "", sourceTurn: Long = 0): Pair<String, Long> {
        val d = db ?: return "unavailable" to 0L
        val t = clean(text)
        if (t.length < 3) return "empty" to 0L
        if (Learn.secret(t)) return "secret" to 0L
        return d.save(kind, subject, t, related, sourceTurn).also { if (it.first != "same") changed() }
    }

    fun update(id: Long, text: String, kind: String? = null, subject: String? = null): Boolean {
        val d = db ?: return false
        if (Learn.secret(text)) return false
        return d.update(id, text, kind, subject).also { if (it) changed() }
    }

    fun delete(id: Long) { db?.delete(id); changed() }

    /** The journal after a chat was saved (Agent.persist). */
    fun journal(conv: String, h: JSONArray) { try { db?.journal(conv, h) } catch (_: Exception) { } }

    // ---------------- migration ----------------

    /**
     * One-time, idempotent import (Desktop migrateMind): every old chat into the journal (UNIQUE(conv, turn) makes a
     * re-run add nothing) and the old memory list into the memories table. Each step sets its meta flag only after it
     * finishes, so a failure retries next launch. Chat files are never changed or deleted.
     */
    fun migrate(d: Db, chats: List<String>, load: (String) -> JSONObject?, oldFacts: List<String>): Pair<Int, Int> {
        var turns = 0; var mems = 0
        if (d.meta("mig_chats") != "1") {
            for (id in chats) {
                val o = load(id) ?: continue
                val h = o.optJSONArray("history") ?: continue
                val at = (o.optLong("updated").takeIf { it > 0 } ?: System.currentTimeMillis()) / 1000
                turns += d.journal(id, h, at, full = true)
            }
            d.setMeta("mig_chats", "1")
            d.setMeta("archived", JSONArray(chats.filter { it != MAIN }).toString())
        }
        if (d.meta("mig_memory") != "1") {
            mems += importFacts(d, oldFacts)
            d.setMeta("mig_memory", "1")
        }
        return turns to mems
    }

    /** Plain old facts → memories (dedupe makes this safe to repeat). Returns how many rows were added. */
    fun importFacts(d: Db, facts: List<String>): Int {
        var n = 0
        for (f in facts) {
            val t = clean(f); if (t.length < 3 || Learn.secret(t)) continue
            val (k, s) = guessKind(t)
            if (d.save(k, s, t).first == "saved") n++
        }
        return n
    }

    /** Chats that existed before one mind (read-only in Archive). */
    fun archived(): Set<String> = try {
        val a = JSONArray(db?.meta("archived").orEmpty().ifBlank { "[]" }); (0 until a.length()).map { a.getString(it) }.toSet()
    } catch (_: Exception) { emptySet() }

    // ---------------- tools ----------------

    val toolNames = setOf("memory_search", "memory_save", "memory_update")

    private fun fn(name: String, desc: String, props: JSONObject, req: List<String>) = JSONObject().put("type", "function").put("function",
        JSONObject().put("name", name).put("description", desc).put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
    private fun str(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun kindProp() = JSONObject().put("type", "string").put("enum", JSONArray(KINDS))

    fun tools(): List<JSONObject> = listOf(
        fn("memory_search", "Search what you know about the user (profile, preferences, people, projects, decisions, facts). Call it before answering anything about them, their people, plans or past conversations.",
            JSONObject().put("query", str("Words to look for, e.g. 'Gloria' or 'food'")).put("kind", kindProp()), listOf("query")),
        fn("memory_save", "Save one lasting line: profile, preference, person, project state, decision or commitment. Keep the user's conditions exactly; set inferred=true when you inferred it. A newer version of a line replaces the old one automatically.",
            JSONObject().put("kind", kindProp()).put("subject", str("Who or what it is about, e.g. 'You', 'Gloria', 'HomeGlow'"))
                .put("text", str("One line")).put("related", str("Other subjects, comma-separated")).put("inferred", JSONObject().put("type", "boolean")),
            listOf("kind", "subject", "text")),
        fn("memory_update", "Rewrite a saved line that changed (id from memory_search). Never keep two versions.",
            JSONObject().put("id", JSONObject().put("type", "integer")).put("text", str("The new line")).put("kind", kindProp()).put("subject", str("New subject (optional)")),
            listOf("id", "text")),
    )

    fun line(m: Mem) = "#${m.id} [${m.kind}] ${m.subject}: ${m.text}"

    fun run(name: String, args: JSONObject): String {
        if (db == null) return "Memory isn't available on this phone right now."
        return when (name) {
            "memory_search" -> {
                val hits = db!!.search(args.optString("query"), args.optString("kind").ifBlank { null }, 12)
                if (hits.isEmpty()) "Nothing saved about \u201c${args.optString("query")}\u201d. Try other words, or search_chat for earlier conversations."
                else hits.joinToString("\n") { line(it) }
            }
            "memory_save" -> {
                val text = clean(args.optString("text"), args.optBoolean("inferred"))
                val (r, id) = save(args.optString("kind"), args.optString("subject"), text, args.optString("related"))
                when (r) {
                    "saved" -> "Saved #$id."
                    "updated" -> "Updated #$id (the newer line replaced the old one)."
                    "same" -> "Already saved as #$id."
                    "secret" -> "Not saved: that looks like a password, code or account number, which Blaze never keeps."
                    else -> "Nothing to save."
                }
            }
            "memory_update" -> {
                val id = args.optLong("id")
                if (update(id, clean(args.optString("text")), args.optString("kind").ifBlank { null }, args.optString("subject").ifBlank { null })) "Updated #$id."
                else "No saved line #$id (memory_search gives the ids)."
            }
            else -> "Unknown tool"
        }
    }

    /** search_chat over the journal: every conversation, archived chats included. */
    fun searchChat(query: String, limit: Int): String {
        val d = db ?: return "Search isn't available on this phone."
        val hits = try { d.searchJournal(query, null, limit.coerceIn(1, 20)) } catch (e: Exception) { return "Search failed: ${e.message}" }
        if (words(query).isEmpty()) return "Give me some words to search for."
        if (hits.isEmpty()) return "Nothing in your conversations matches \u201c$query\u201d."
        return hits.joinToString("\n") { h -> "turn j${h.id} (${if (h.conv == MAIN) "main" else h.conv} #${h.turn}, ${h.role}): ${h.snippet}" } +
            "\nread_turn {id} returns a whole message (e.g. j${hits.first().id})."
    }

    fun readTurn(id: String): String? {
        val n = id.trim().removePrefix("turn").trim().takeIf { it.startsWith("j") }?.drop(1)?.toLongOrNull() ?: return null
        val t = db?.turn(n) ?: return "Error: no journal turn j$n."
        return "[${t.role}] (${t.conv} #${t.turn}) " + t.content.take(12_000)
    }
}
