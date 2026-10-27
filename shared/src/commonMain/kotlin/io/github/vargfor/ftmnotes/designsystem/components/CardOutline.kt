package io.github.vargfor.ftmnotes.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A little more definition using the existing neutral palette and corner shape. */
@Composable
fun cardBorder() = BorderStroke(1.25.dp, MaterialTheme.colorScheme.outlineVariant)

@Composable
fun Modifier.cardOutline(): Modifier = border(cardBorder(), MaterialTheme.shapes.medium)
