package com.noted.app.ui

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FeedbackLinksTest {

    @Test
    fun bugLinkFillsTheFormFields() {
        val uri = Uri.parse(FeedbackLinks.bug("1.1.0", "Google Pixel 9 Pro", "16 (API 36)"))
        assertEquals("/niquitoooo/Noted/issues/new", uri.path)
        assertEquals("fallo.yml", uri.getQueryParameter("template"))
        assertEquals("1.1.0", uri.getQueryParameter("version"))
        assertEquals("Google Pixel 9 Pro", uri.getQueryParameter("telefono"))
        assertEquals("16 (API 36)", uri.getQueryParameter("android"))
    }

    @Test
    fun ideaLinkUsesTheIdeaForm() {
        val uri = Uri.parse(FeedbackLinks.idea("1.1.0"))
        assertEquals("funcion.yml", uri.getQueryParameter("template"))
        assertEquals("1.1.0", uri.getQueryParameter("version"))
    }
}
