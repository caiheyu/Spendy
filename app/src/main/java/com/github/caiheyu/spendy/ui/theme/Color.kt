package com.github.caiheyu.spendy.ui.theme

import androidx.compose.ui.graphics.Color

// Shared with prototype/assets/common.css. Keep brand and ledger colors stable in both modes.
val SpendyPrimary = Color(0xFF5560E9)
val SpendyGradientEnd = Color(0xFF8A5CF6)
val Expense = Color(0xFFF0564A)
val Income = Color(0xFF1FA97A)
val AccountCard = Color(0xFF1C1E2B)
val AccountIncome = Color(0xFF3ED598)
val AccountExpense = Color(0xFFFF8A80)

data class SpendyColors(
    val background: Color,
    val card: Color,
    val ink: Color,
    val secondaryInk: Color,
    val mutedInk: Color,
    val line: Color,
)

val SpendyLight = SpendyColors(
    background = Color(0xFFF4F5FA), card = Color.White,
    ink = Color(0xFF1B1C28), secondaryInk = Color(0xFF71748A),
    mutedInk = Color(0xFF686D80), line = Color(0xFFECEDF4),
)
val SpendyDark = SpendyColors(
    background = Color(0xFF0E0F16), card = Color(0xFF171923),
    ink = Color(0xFFF1F2F8), secondaryInk = Color(0xFF9BA0B5),
    mutedInk = Color(0xFF858CA3), line = Color(0xFF242737),
)
