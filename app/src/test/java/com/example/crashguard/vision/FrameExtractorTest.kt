package com.example.crashguard.vision

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FrameExtractorTest {

    @Test
    fun testNonExistentFile_returnsEmptyListWithoutThrowing() {
        val fakeFile = File("non_existent_video_file_12345.mp4")
        val frames = FrameExtractor.extractFrames(fakeFile)
        assertTrue("Expected empty frames list for missing file", frames.isEmpty())
    }

    @Test
    fun testEmptyFile_returnsEmptyListWithoutThrowing() {
        val tempFile = File.createTempFile("empty_test", ".mp4")
        try {
            val frames = FrameExtractor.extractFrames(tempFile)
            assertTrue("Expected empty frames list for 0-byte file", frames.isEmpty())
        } finally {
            tempFile.delete()
        }
    }
}
