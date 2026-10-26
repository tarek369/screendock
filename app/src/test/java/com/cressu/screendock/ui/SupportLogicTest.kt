package com.cressu.screendock.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportLogicTest {

    private val diag = SupportDiagnostics(
        appVersion = "0.3.6",
        versionCode = 11,
        pro = false,
        manufacturer = "OnePlus",
        model = "OPD2415",
        androidRelease = "16",
        sdkInt = 36,
        network = "Wi-Fi 5 GHz",
        vpnOn = true,
        receiverRunning = true,
        resolution = "3392x2400",
        fps = 60,
        successfulSessions = 0,
    )

    @Test fun connectHelpShowsAfterTwoMinutesForFirstTimers() {
        val wait = ConnectHelpPolicy.DELAY_MS
        assertTrue(ConnectHelpPolicy.shouldShow(ready = true, waitedMs = wait, successfulSessions = 0, dismissed = false))
        assertFalse(ConnectHelpPolicy.shouldShow(ready = true, waitedMs = wait - 1, successfulSessions = 0, dismissed = false))
    }

    @Test fun connectHelpHiddenWhenNotReadyDismissedOrAlreadyConnectedBefore() {
        val wait = ConnectHelpPolicy.DELAY_MS
        assertFalse(ConnectHelpPolicy.shouldShow(ready = false, waitedMs = wait, successfulSessions = 0, dismissed = false))
        assertFalse(ConnectHelpPolicy.shouldShow(ready = true, waitedMs = wait, successfulSessions = 0, dismissed = true))
        assertFalse(ConnectHelpPolicy.shouldShow(ready = true, waitedMs = wait, successfulSessions = 1, dismissed = false))
    }

    @Test fun subjectNamesProblemAndSender() {
        assertEquals(
            "ScreenDock: device doesn't appear (Mac)",
            SupportEmail.subject(SupportProblem.NOT_FOUND, SenderKind.MAC),
        )
        assertEquals(
            "ScreenDock: no sound (iPhone)",
            SupportEmail.subject(SupportProblem.NO_SOUND, SenderKind.IPHONE),
        )
    }

    @Test fun bodyCarriesPromptAndEveryDiagnostic() {
        val body = SupportEmail.body("Tell us what happened:", SupportProblem.BLACK_SCREEN, SenderKind.IPAD, diag)
        assertTrue(body.startsWith("Tell us what happened:\n\n\n"))
        listOf(
            "Problem: black screen",
            "Sender: iPad",
            "App: ScreenDock 0.3.6 (11), Free",
            "Device: OnePlus OPD2415",
            "Android: 16 (API 36)",
            "Network: Wi-Fi 5 GHz, VPN on",
            "Receiver: running, 3392x2400 @ 60 fps",
            "Successful sessions: 0",
        ).forEach { assertTrue("missing: $it", body.contains(it)) }
    }

    @Test fun bodyReportsProAndStoppedReceiverAndVpnOff() {
        val body = SupportEmail.body("", SupportProblem.OTHER, SenderKind.MAC,
            diag.copy(pro = true, receiverRunning = false, vpnOn = false))
        assertTrue(body.contains("App: ScreenDock 0.3.6 (11), Pro"))
        assertTrue(body.contains("Network: Wi-Fi 5 GHz, VPN off"))
        assertTrue(body.contains("Receiver: stopped"))
    }

    @Test fun wifiBandFromFrequency() {
        assertEquals("2.4", WifiBand.fromMhz(2437))
        assertEquals("5", WifiBand.fromMhz(5180))
        assertEquals("6", WifiBand.fromMhz(5955))
        assertEquals(null, WifiBand.fromMhz(-1))
        assertEquals(null, WifiBand.fromMhz(0))
    }

    @Test fun mailtoEncodesSpacesNewlinesAndAmpersands() {
        val uri = SupportEmail.mailtoUri("a b&c", "x\ny")
        assertEquals("mailto:${SupportEmail.ADDRESS}?subject=a%20b%26c&body=x%0Ay", uri)
    }
}
