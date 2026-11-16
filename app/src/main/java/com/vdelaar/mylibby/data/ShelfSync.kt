package com.vdelaar.mylibby.data

import com.vdelaar.mylibby.core.database.PendingAction
import com.vdelaar.mylibby.core.database.ShelfBookEntity
import com.vdelaar.mylibby.core.database.ShelfEntity

/** The shelf the app keeps as "Favourites"; a reader's own shelf may not take either spelling. */
val RESERVED_SHELF_NAMES = listOf("Favorites", "Favourites")

const val MAX_SHELF_NAME = 60

enum class ShelfNameError { BLANK, TOO_LONG, RESERVED, DUPLICATE }

/** A shelf name as stored: trimmed, inner whitespace collapsed. */
fun cleanShelfName(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

/**
 * Why [raw] cannot name a shelf, or null when it can. Names are unique per reader, ignoring case (Grimmory insists on
 * that); [existing] are the other shelves' names (leave the shelf being renamed out of it).
 */
fun checkShelfName(raw: String, existing: Collection<String>): ShelfNameError? {
    val name = cleanShelfName(raw)
    return when {
        name.isEmpty() -> ShelfNameError.BLANK
        name.length > MAX_SHELF_NAME -> ShelfNameError.TOO_LONG
        RESERVED_SHELF_NAMES.any { it.equals(name, ignoreCase = true) } -> ShelfNameError.RESERVED
        existing.any { it.equals(name, ignoreCase = true) } -> ShelfNameError.DUPLICATE
        else -> null
    }
}

/** A shelf as Grimmory reports it. */
data class RemoteShelf(
    val id: Long,
    val name: String,
    val icon: String?,
    val iconType: String?,
    val publicShelf: Boolean,
    /** Somebody else's public shelf. */
    val shared: Boolean,
    val bookCount: Int,
)

/** What reading the server's shelves means for the shelves on the device. */
data class ShelfPlan(
    /** Shelves the device does not have yet. */
    val add: List<RemoteShelf>,
    /** Device shelves that turn out to be the server's (same name, never sent): local id to server shelf. */
    val link: Map<Long, RemoteShelf>,
    /** Device shelves with nothing to send: take over what the server says (local id to server shelf). */
    val refresh: Map<Long, RemoteShelf>,
    /** Shelves gone from the server that still have changes to send: send them again as new (local ids). */
    val recreate: List<Long>,
    /** Shelves gone from the server and unchanged here: delete them (local ids). */
    val drop: List<Long>,
)

/**
 * Compares the device's shelves with the server's. The server decides, except where the device has something to send:
 * a rename waiting to go out is kept, and a shelf that was deleted on the server while it had unsent changes comes back.
 * [remote] must already leave out the Favourites shelf.
 */
fun planShelves(local: List<ShelfEntity>, remote: List<RemoteShelf>): ShelfPlan {
    val remoteById = remote.associateBy { it.id }
    val add = ArrayList<RemoteShelf>()
    val link = LinkedHashMap<Long, RemoteShelf>()
    val refresh = LinkedHashMap<Long, RemoteShelf>()
    val recreate = ArrayList<Long>()
    val drop = ArrayList<Long>()

    val linkedRemote = HashSet<Long>()
    for (s in local) {
        val id = s.remoteId ?: continue
        val r = remoteById[id]
        if (r == null) {
            // Gone from the server. Wanted gone, unchanged or somebody else's: let it go. Changed here: send it again.
            if (s.pending == PendingAction.DELETE || s.pending == PendingAction.NONE || s.shared) drop += s.localId else recreate += s.localId
            continue
        }
        linkedRemote += id
        if (s.pending == PendingAction.NONE) refresh[s.localId] = r
    }
    // Never-sent shelves take the server's shelf of the same name instead of making a second one.
    val unsent = local.filter { it.remoteId == null && it.pending != PendingAction.DELETE }
    val takenLocal = HashSet<Long>()
    for (r in remote) {
        if (r.id in linkedRemote) continue
        // Somebody else's shelf is never ours, whatever it is called.
        val match = if (r.shared) null else unsent.firstOrNull { it.localId !in takenLocal && it.name.equals(r.name, ignoreCase = true) }
        if (match != null) { takenLocal += match.localId; link[match.localId] = r } else add += r
    }
    return ShelfPlan(add, link, refresh, recreate, drop)
}

/** What reading the server's books of one shelf means for the books on the device's copy of it. */
data class MemberPlan(
    /** On the server, not here: add. */
    val add: Set<Long>,
    /** Here and fine (no change waiting), no longer on the server: remove. */
    val remove: Set<Long>,
    /** Here with "still to send", but the server already has them: nothing left to send. */
    val confirm: Set<Long>,
)

/**
 * [local] are the shelf's rows, [remoteIds] the Grimmory books on the server's shelf. Books of the device (negative
 * ids) are never on the server and are left alone, as are rows with a change still to send.
 */
fun planMembers(local: List<ShelfBookEntity>, remoteIds: Set<Long>): MemberPlan {
    // A row waiting to be deleted stays until the server has been told, so it is not added back.
    val add = remoteIds - local.map { it.bookId }.toSet()
    val remove = local.filter { it.bookId > 0 && it.pending == PendingAction.NONE && it.bookId !in remoteIds }.map { it.bookId }.toSet()
    val confirm = local.filter { it.pending == PendingAction.CREATE && it.bookId in remoteIds }.map { it.bookId }.toSet()
    return MemberPlan(add, remove, confirm)
}
