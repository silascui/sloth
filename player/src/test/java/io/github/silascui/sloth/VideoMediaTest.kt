package io.github.silascui.sloth

import org.junit.Assert.*
import org.junit.Test

class VideoMediaTest {
    @Test fun urlRevisionChangesSourceButNotLogicalSessionId() {
        val a = VideoMedia("episode", "https://example.invalid/a?signature=old")
        val b = a.copy(url = "https://example.invalid/a?signature=new")
        assertEquals(a.id, b.id); assertNotEquals(a.sourceKey, b.sourceKey)
        assertFalse(a.sourceKey.contains("signature"))
    }
    @Test fun equalInputsHaveStableSourceIdentity() {
        assertEquals(VideoMedia("a", "asset:///a").sourceKey, VideoMedia("a", "asset:///a").sourceKey)
    }
    @Test(expected = IllegalArgumentException::class) fun invalidPositionFailsBeforePreparing() { VideoMedia("a", "asset:///a", initialPositionMs = -1) }
    @Test(expected = IllegalArgumentException::class) fun blankIdIsRejected() { VideoMedia("", "asset:///a") }
}
