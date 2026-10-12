package com.cressu.screendock.service

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationCopyTest {
    private val t = object : NotificationTexts {
        override fun ready() = "Ready"
        override fun starting() = "Starting"
        override fun stopped() = "Stopped"
        override fun error() = "Error"
        override fun connected(sender: String) = "Connected $sender"
        override fun mirroring(sender: String) = "Mirroring $sender"
        override fun playing(title: String) = "Playing $title"
        override fun playingFrom(sender: String) = "PlayingFrom $sender"
        override fun videoFrom(sender: String) = "VideoFrom $sender"
        override fun pin(pin: String) = "PIN $pin"
    }
    private fun m(
        enabled: Boolean = true, state: ReceiverState = ReceiverState.WAITING, mode: MediaMode = MediaMode.NONE,
        sender: String? = null, track: String? = null, pin: String? = null, error: String? = null,
    ) = NotificationModel(enabled, state, mode, sender, track, pin, error)

    @Test fun `waiting is ready`() = assertEquals("Ready", NotificationCopy.title(m(), t))
    @Test fun `disabled is stopped`() = assertEquals("Stopped", NotificationCopy.title(m(enabled = false), t))
    @Test fun `idle with error shows error`() =
        assertEquals("Error", NotificationCopy.title(m(state = ReceiverState.IDLE, error = "x"), t))
    @Test fun `mirroring names sender`() = assertEquals("Mirroring Tarek's Mac",
        NotificationCopy.title(m(state = ReceiverState.CONNECTED, mode = MediaMode.MIRROR, sender = "Tarek's Mac"), t))
    @Test fun `audio prefers track title`() = assertEquals("Playing Song",
        NotificationCopy.title(m(state = ReceiverState.CONNECTED, mode = MediaMode.AUDIO, sender = "iPhone", track = "Song"), t))
    @Test fun `audio without title uses sender`() = assertEquals("PlayingFrom iPhone",
        NotificationCopy.title(m(state = ReceiverState.CONNECTED, mode = MediaMode.AUDIO, sender = "iPhone"), t))
    @Test fun `video without title uses sender`() = assertEquals("VideoFrom iPad",
        NotificationCopy.title(m(state = ReceiverState.CONNECTED, mode = MediaMode.VIDEO, sender = "iPad"), t))
    @Test fun `connected before media shows connected`() = assertEquals("Connected iPad",
        NotificationCopy.title(m(state = ReceiverState.CONNECTED, sender = "iPad"), t))
    @Test fun `pin wins while pairing`() = assertEquals("PIN 1234", NotificationCopy.title(m(pin = "1234"), t))
}
