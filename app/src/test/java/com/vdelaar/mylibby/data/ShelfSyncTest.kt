package com.vdelaar.mylibby.data

import com.vdelaar.mylibby.core.database.PendingAction
import com.vdelaar.mylibby.core.database.ShelfBookEntity
import com.vdelaar.mylibby.core.database.ShelfEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfSyncTest {
    private fun local(id: Long, name: String, remote: Long? = null, pending: PendingAction = PendingAction.NONE, shared: Boolean = false) =
        ShelfEntity(localId = id, remoteId = remote, name = name, pending = pending, shared = shared)

    private fun remote(id: Long, name: String, shared: Boolean = false) = RemoteShelf(id, name, null, null, false, shared, 0)

    private fun row(book: Long, pending: PendingAction = PendingAction.NONE) = ShelfBookEntity(1, book, 0, pending)

    // Names ------------------------------------------------------------------------------------

    @Test fun aGoodNameIsAccepted() = assertNull(checkShelfName("Summer reads", listOf("Dutch classics")))

    @Test fun blankNamesAreRefused() {
        assertEquals(ShelfNameError.BLANK, checkShelfName("", emptyList()))
        assertEquals(ShelfNameError.BLANK, checkShelfName("   \n ", emptyList()))
    }

    @Test fun longNamesAreRefused() {
        assertNull(checkShelfName("x".repeat(MAX_SHELF_NAME), emptyList()))
        assertEquals(ShelfNameError.TOO_LONG, checkShelfName("x".repeat(MAX_SHELF_NAME + 1), emptyList()))
    }

    @Test fun theFavouritesNameIsKeptForTheBuiltInShelf() {
        assertEquals(ShelfNameError.RESERVED, checkShelfName("Favorites", emptyList()))
        assertEquals(ShelfNameError.RESERVED, checkShelfName(" favourites ", emptyList()))
    }

    @Test fun namesAreUniqueIgnoringCaseAndSpacing() {
        assertEquals(ShelfNameError.DUPLICATE, checkShelfName("to  READ", listOf("To read")))
    }

    @Test fun namesAreCleaned() = assertEquals("Sci Fi", cleanShelfName("  Sci \t Fi \n"))

    // Reading the server's shelves ---------------------------------------------------------------

    @Test fun newServerShelvesAreAdded() {
        val plan = planShelves(emptyList(), listOf(remote(10, "Sci-fi"), remote(11, "Holiday")))
        assertEquals(listOf(10L, 11L), plan.add.map { it.id })
        assertTrue(plan.link.isEmpty() && plan.drop.isEmpty() && plan.recreate.isEmpty())
    }

    @Test fun aShelfMadeHereWithTheServersNameBecomesThatShelf() {
        val plan = planShelves(listOf(local(1, "holiday", pending = PendingAction.CREATE)), listOf(remote(11, "Holiday")))
        assertEquals(11L, plan.link.getValue(1).id)
        assertTrue("no second shelf", plan.add.isEmpty())
    }

    @Test fun aSharedShelfIsNeverTakenOverByName() {
        val plan = planShelves(listOf(local(1, "Holiday", pending = PendingAction.CREATE)), listOf(remote(11, "Holiday", shared = true)))
        assertTrue(plan.link.isEmpty())
        assertEquals(listOf(11L), plan.add.map { it.id })
    }

    @Test fun twoShelvesHereNeverTakeTheSameServerShelf() {
        val plan = planShelves(
            listOf(local(1, "Holiday", pending = PendingAction.CREATE), local(2, "HOLIDAY", pending = PendingAction.CREATE)),
            listOf(remote(11, "Holiday")),
        )
        assertEquals(1, plan.link.size)
    }

    @Test fun unchangedShelvesTakeOverTheServersNameAndIcon() {
        val plan = planShelves(listOf(local(1, "Old", remote = 10)), listOf(remote(10, "New")))
        assertEquals("New", plan.refresh.getValue(1).name)
    }

    @Test fun aRenameWaitingToBeSentIsKept() {
        val plan = planShelves(listOf(local(1, "Mine", remote = 10, pending = PendingAction.UPDATE)), listOf(remote(10, "Theirs")))
        assertTrue(plan.refresh.isEmpty())
    }

    @Test fun aShelfDeletedOnTheServerIsDeletedHere() {
        val plan = planShelves(listOf(local(1, "Gone", remote = 10)), emptyList())
        assertEquals(listOf(1L), plan.drop)
    }

    @Test fun aShelfDeletedOnTheServerButChangedHereIsSentAgain() {
        val plan = planShelves(listOf(local(1, "Edited", remote = 10, pending = PendingAction.UPDATE)), emptyList())
        assertEquals(listOf(1L), plan.recreate)
        assertTrue(plan.drop.isEmpty())
    }

    @Test fun aShelfIDeletedAndTheServerLostTooIsDroppedForGood() {
        assertEquals(listOf(1L), planShelves(listOf(local(1, "X", remote = 10, pending = PendingAction.DELETE)), emptyList()).drop)
    }

    @Test fun aSharedShelfThatDisappearsIsDropped() {
        assertEquals(listOf(1L), planShelves(listOf(local(1, "X", remote = 10, shared = true)), emptyList()).drop)
    }

    @Test fun aShelfOnlyOnTheDeviceIsLeftAlone() {
        val plan = planShelves(listOf(local(1, "Mine", pending = PendingAction.CREATE)), listOf(remote(10, "Other")))
        assertTrue(plan.drop.isEmpty() && plan.recreate.isEmpty() && plan.link.isEmpty())
        assertEquals(listOf(10L), plan.add.map { it.id })
    }

    // Reading the books of one shelf ---------------------------------------------------------------

    @Test fun booksOnTheServerShelfAreAdded() {
        val plan = planMembers(listOf(row(1)), setOf(1, 2, 3))
        assertEquals(setOf(2L, 3L), plan.add)
        assertTrue(plan.remove.isEmpty())
    }

    @Test fun booksTakenOffOnTheServerLeaveTheShelf() {
        assertEquals(setOf(2L), planMembers(listOf(row(1), row(2)), setOf(1)).remove)
    }

    @Test fun booksWaitingToBeSentAreNotRemoved() {
        val plan = planMembers(listOf(row(1, PendingAction.CREATE)), emptySet())
        assertTrue(plan.remove.isEmpty())
    }

    @Test fun booksOfTheDeviceAreNeverRemovedByTheServer() {
        assertTrue(planMembers(listOf(row(-4)), emptySet()).remove.isEmpty())
    }

    @Test fun aBookIRemovedIsNotAddedBackBeforeTheServerKnows() {
        val plan = planMembers(listOf(row(1, PendingAction.DELETE)), setOf(1))
        assertTrue(plan.add.isEmpty())
        assertTrue(plan.remove.isEmpty())
    }

    @Test fun aBookTheServerAlreadyHasNeedsNothingMoreSent() {
        assertEquals(setOf(1L), planMembers(listOf(row(1, PendingAction.CREATE)), setOf(1)).confirm)
    }
}
