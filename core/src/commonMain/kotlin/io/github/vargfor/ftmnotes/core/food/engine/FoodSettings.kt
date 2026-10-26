package io.github.vargfor.ftmnotes.core.food.engine

import io.github.vargfor.ftmnotes.core.settings.*

fun SettingsSnapshot.foodPolicy(decimalComma: Boolean = false): FoodPolicy = FoodPolicy(
    target = this[Flags.foodUnits], volume = this[Flags.foodVolume], keepSpoons = this[Flags.foodKeepSpoons],
    useDensities = this[Flags.foodUseDensities], rounding = this[Flags.foodRounding], customStep = this[Flags.foodRoundingStep],
    decimalComma = decimalComma, fanOvenHint = this[Flags.foodFanHint], densities = this[Flags.foodDensities],
)
