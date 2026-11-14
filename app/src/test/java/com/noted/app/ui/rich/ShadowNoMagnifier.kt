package com.noted.app.ui.rich

import android.graphics.Point
import android.widget.Magnifier
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * La lupa del Editor no anda en Robolectric (la ventana no tiene insets): sin esto, arrastrar
 * para seleccionar en un test se cae dentro de `Magnifier`. La lupa no se dibuja y no tiene
 * lugar; la selección se calcula igual.
 */
@Implements(Magnifier::class)
class ShadowNoMagnifier {
    @Implementation
    protected fun show(sourceCenterX: Float, sourceCenterY: Float, magnifierCenterX: Float, magnifierCenterY: Float) = Unit

    @Implementation
    protected fun dismiss() = Unit

    @Implementation
    protected fun update() = Unit

    @Implementation
    protected fun getPosition(): Point? = null

    @Implementation
    protected fun getSourcePosition(): Point? = null
}
