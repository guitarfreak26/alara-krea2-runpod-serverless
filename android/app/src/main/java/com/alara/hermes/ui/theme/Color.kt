package com.alara.hermes.ui.theme

import androidx.compose.ui.graphics.Color

// AMOLED-first palette. True black ground, restrained neutral surfaces,
// one calm accent. No gold, no gradients, no neon.
object HermesColors {
    // Grounds
    val Black = Color(0xFF000000)
    val Surface1 = Color(0xFF0C0C10) // list rows, sheets
    val Surface2 = Color(0xFF15151B) // cards, composer, incoming bubbles
    val Surface3 = Color(0xFF1E1E26) // pressed / elevated
    val Border = Color(0xFF26262E)
    val BorderSubtle = Color(0xFF1A1A21)

    // Text
    val TextPrimary = Color(0xFFECECF1)
    val TextSecondary = Color(0xFFA7A7B4)
    val TextTertiary = Color(0xFF6E6E7A)

    // Accent — muted periwinkle; readable on black, not neon.
    val Accent = Color(0xFF8B87F7)
    val AccentDim = Color(0xFF5B58B8)
    val OnAccent = Color(0xFF0A0A14)
    // Outgoing bubble: deep accent-tinted surface so user text stays white-on-dark.
    val BubbleOutgoing = Color(0xFF2E2C52)
    val BubbleIncoming = Surface2

    // Status
    val Positive = Color(0xFF57C978)
    val Caution = Color(0xFFE2B93B)
    val Danger = Color(0xFFE5645E)
    val Running = Accent

    // Light theme counterparts (secondary priority, kept coherent)
    val LightBackground = Color(0xFFF7F7FA)
    val LightSurface1 = Color(0xFFFFFFFF)
    val LightSurface2 = Color(0xFFF0F0F5)
    val LightBorder = Color(0xFFE2E2EA)
    val LightTextPrimary = Color(0xFF17171D)
    val LightTextSecondary = Color(0xFF5C5C68)
    val LightAccent = Color(0xFF5A56D6)
}
