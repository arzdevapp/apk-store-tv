package com.arzdev.appupdater

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCleanupTest {
    private val root = Files.createTempDirectory("updater-cleanup").toFile()

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun deletesUpdaterApksExceptTheKeptFile() {
        val old = File(root, "update_old.apk").apply { writeText("old") }
        val partial = File(root, "update_partial.apk").apply { writeText("partial") }
        val keep = File(root, "update_current.apk").apply { writeText("current") }

        val deleted = cleanupDownloadedApks(listOf(root), keep)

        assertEquals(2, deleted)
        assertFalse(old.exists())
        assertFalse(partial.exists())
        assertTrue(keep.exists())
    }

    @Test
    fun preservesFilesOutsideUpdaterNamingPattern() {
        val ordinaryApk = File(root, "ordinary.apk").apply { writeText("apk") }
        val note = File(root, "update_note.txt").apply { writeText("note") }
        val directory = File(root, "update_folder.apk").apply { mkdir() }

        val deleted = cleanupDownloadedApks(listOf(root))

        assertEquals(0, deleted)
        assertTrue(ordinaryApk.exists())
        assertTrue(note.exists())
        assertTrue(directory.isDirectory)
    }
}
