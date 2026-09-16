package com.screenshot.recording.detector.screenshot_recording_detector

import android.net.Uri
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScreenshotRecordingDetectorPluginTest {
    private class Reply : MethodChannel.Result {
        var value: Any? = null
        var error: String? = null
        var notImplemented = false
        override fun success(result: Any?) { value = result }
        override fun error(code: String, message: String?, details: Any?) { error = code }
        override fun notImplemented() { notImplemented = true }
    }

    @Test fun rootNotificationIsNotTheQueryEndpoint() {
        assertNotEquals(Uri.parse("content://media/external"), LegacyMediaQuery.collection)
        assertEquals("content://media/external/images/media", LegacyMediaQuery.collection.toString())
    }

    @Test fun providerIllegalStateDoesNotEscapeBoundary() {
        var failure: RuntimeException? = null
        val result = LegacyMediaQuery.safely<Int>({ failure = it }) {
            throw IllegalStateException("Unknown URL: content://media/external is hidden API")
        }
        assertNull(result)
        assertTrue(failure is IllegalStateException)
    }

    @Test fun permissionAndInvalidQueryFailuresAreContained() {
        listOf(SecurityException("revoked"), IllegalArgumentException("column")).forEach { exception ->
            var caught: RuntimeException? = null
            assertNull(LegacyMediaQuery.safely<Int>({ caught = it }) { throw exception })
            assertSame(exception, caught)
        }
    }

    @Test fun screenshotHeuristicIsNotAnyScreenOrCaptureName() {
        assertTrue(LegacyMediaQuery.looksLikeScreenshot("Pictures/Screenshots/Screenshot_123.png"))
        assertTrue(LegacyMediaQuery.looksLikeScreenshot("SCREEN_SHOT.png"))
        assertFalse(LegacyMediaQuery.looksLikeScreenshot("landscape_screen.png"))
        assertFalse(LegacyMediaQuery.looksLikeScreenshot("camera_capture.jpg"))
    }

    @Test fun repeatedInitializeAndDisposeWithoutActivityAreSafe() {
        val plugin = ScreenshotRecordingDetectorPlugin()
        repeat(2) { plugin.onMethodCall(MethodCall("initialize", null), Reply()) }
        val status = Reply()
        plugin.onMethodCall(MethodCall("getDetectionStatus", null), status)
        val map = status.value as Map<*, *>
        assertEquals("inactive", map["screenshot"])
        assertEquals("unsupported", map["recording"])
        assertNull(map["isRecording"])
        repeat(2) { plugin.onMethodCall(MethodCall("dispose", null), Reply()) }
    }

    @Test fun invalidBlockArgumentReturnsErrorInsteadOfCastCrash() {
        val reply = Reply()
        ScreenshotRecordingDetectorPlugin().onMethodCall(MethodCall("setBlockScreenshots", "true"), reply)
        assertEquals("INVALID_ARGUMENT", reply.error)
    }

    @Test fun unknownMethodIsNotImplemented() {
        val reply = Reply()
        ScreenshotRecordingDetectorPlugin().onMethodCall(MethodCall("unknown", null), reply)
        assertTrue(reply.notImplemented)
    }
}
