package io.github.vargfor.ftmnotes.core.food.engine

import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.core.settings.WireValue
import kotlin.math.*

enum class UnitMode(override val wire: String) : WireValue { Original("original"), Metric("metric"), US("us") }
enum class MetricVolume(override val wire: String) : WireValue { Auto("auto"), Millilitres("ml"), Decilitres("dl") }
enum class FoodRounding(override val wire: String) : WireValue { Kitchen("kitchen"), Exact("exact"), Custom("custom") }
data class FoodPolicy(
    val target: UnitMode = UnitMode.Metric, val volume: MetricVolume = MetricVolume.Auto,
    val keepSpoons: Boolean = true, val useDensities: Boolean = true,
    val rounding: FoodRounding = FoodRounding.Kitchen, val customStep: Double = 1.0,
    val decimalComma: Boolean = false, val fanOvenHint: Boolean = false,
    val densities: Map<String, Double> = emptyMap(),
)
data class IngredientDisplay(
    val original: Ingredient, val quantity: Quantity?, val unit: FoodUnit?, val unitText: String?,
    val packageQuantity: Quantity?, val packageUnit: FoodUnit?, val approximate: Boolean,
    val fractionalCount: Boolean, val policy: FoodPolicy,
) {
    fun text(): String {
        if (quantity == null) return original.raw.ifBlank { original.name }
        val us = policy.target == UnitMode.US || policy.target == UnitMode.Original && unit?.system == UnitSystem.US
        val amount = QuantityFormatter.format(quantity, us, policy.decimalComma, policy.rounding == FoodRounding.Kitchen)
        val pack = packageQuantity?.let { p -> " (" + QuantityFormatter.format(p, us, policy.decimalComma) + " " + (packageUnit?.symbol ?: original.packageUnit.orEmpty()) + ")" }.orEmpty()
        val measure = (unit?.symbol ?: unitText).orEmpty()
        return (if (approximate) "≈ " else "") + amount + pack + (if (measure.isEmpty()) "" else " $measure") + " " + original.name +
            if (original.note.isBlank()) "" else ", ${original.note}"
    }
}

object Densities {
    const val SOURCE = "https://www.kingarthurbaking.com/learn/ingredient-weight-chart"
    /** Approximate grams per US cup, from the publisher's chart; users can override g/ml. */
    val gramsPerCup = linkedMapOf("flour" to 120.0, "all purpose flour" to 120.0, "whole wheat flour" to 113.0,
        "sugar" to 198.0, "granulated sugar" to 198.0, "almond flour" to 96.0, "cocoa" to 84.0, "rolled oats" to 89.0)
    private val aliases = mapOf("hvetemel" to "flour", "sukker" to "sugar", "mandelmel" to "almond flour",
        "kakao" to "cocoa", "havregryn" to "rolled oats", "all-purpose flour" to "all purpose flour")
    fun key(name: String) = name.trim().lowercase().replace('-', ' ').replace(Regex("\\s+"), " ")
    fun find(name: String, overrides: Map<String, Double>): Double? {
        val key = key(name)
        val canonical = aliases[name.lowercase()] ?: aliases[key] ?: key
        (overrides[key] ?: overrides[canonical])?.let { return it.takeIf { value -> value.isFinite() && value > 0 } }
        return gramsPerCup[canonical]?.div(Units.get("us_cup").factor)
    }
}

object FoodEngine {
    fun factor(recipe: RecipeDocument, servings: Double): Double {
        require(servings.isFinite() && servings > 0)
        return servings / recipe.servings.amount
    }

    fun display(ingredient: Ingredient, factor: Double = 1.0, policy: FoodPolicy = FoodPolicy()): IngredientDisplay {
        require(factor.isFinite() && factor > 0)
        var quantity = ingredient.quantity?.let { if (ingredient.scalable) it.scaled(factor) else it }
        val source = Units.find(ingredient.unit)
        if (policy.target == UnitMode.Original && factor == 1.0) return IngredientDisplay(ingredient, ingredient.quantity, source, ingredient.unit,
            ingredient.packageQuantity, Units.find(ingredient.packageUnit), false,
            ingredient.quantity?.let { (source == null || source.dimension == Dimension.Count) && (it.min % 1 != 0.0 || it.max % 1 != 0.0) } ?: false,
            policy.copy(rounding = FoodRounding.Exact))
        var unit = source
        var approx = false
        val spoon = source?.id in setOf("us_tsp", "us_tbsp", "metric_tsp", "metric_tbsp", "au_tbsp")
        if (quantity != null && source != null) {
            val density = if (policy.useDensities) Densities.find(ingredient.name, policy.densities) else null
            val preserveSpoon = policy.keepSpoons && spoon && quantity.max * source.factor < Units.get("us_cup").factor
            if (policy.target != UnitMode.Original && !preserveSpoon && source.dimension == Dimension.Volume && density != null && policy.target == UnitMode.Metric) {
                quantity = Quantity(quantity.min * source.factor * density, quantity.max * source.factor * density)
                unit = Units.get(if (quantity.max >= 1000) "kg" else "g")
                if (unit.id == "kg") quantity = quantity.scaled(0.001)
                approx = true
            } else if (policy.target == UnitMode.US && source.dimension == Dimension.Mass && density != null) {
                val ml = Quantity(quantity.min * source.factor / density, quantity.max * source.factor / density)
                unit = usVolume(ml.max)
                quantity = ml.scaled(1 / unit.factor)
                approx = true
            } else if (!preserveSpoon) {
                val base = quantity.max * source.factor
                unit = when (source.dimension) {
                    Dimension.Mass -> when (policy.target) {
                        UnitMode.Metric -> Units.get(if (base >= 1000) "kg" else "g")
                        UnitMode.US -> Units.get(if (base >= 453.59237) "lb" else "oz")
                        UnitMode.Original -> if (source.system == UnitSystem.Metric) Units.get(if (base >= 1000) "kg" else if (source.id == "mg" && base < 1) "mg" else "g") else source
                    }
                    Dimension.Volume -> when (policy.target) {
                        UnitMode.Metric -> metricVolume(base, policy.volume)
                        UnitMode.US -> usVolume(base)
                        UnitMode.Original -> if (source.system == UnitSystem.US) usVolume(base) else if (source.system == UnitSystem.Metric && !spoon) metricVolume(base, policy.volume) else source
                    }
                    else -> source
                }
                quantity = Units.convert(quantity, source, unit)
            }
            if (unit.dimension != Dimension.Count) quantity = rounded(quantity, unit, policy)
        }
        val fractionCount = quantity != null && (source == null || source.dimension == Dimension.Count) &&
            (quantity.min % 1.0 > 0.0001 || quantity.max % 1.0 > 0.0001)
        var packageQuantity = ingredient.packageQuantity
        var packageUnit = Units.find(ingredient.packageUnit)
        if (packageQuantity != null && packageUnit != null && policy.target != UnitMode.Original) {
            val target = when (packageUnit.dimension) {
                Dimension.Mass -> if (policy.target == UnitMode.Metric) Units.get("g") else Units.get("oz")
                Dimension.Volume -> if (policy.target == UnitMode.Metric) Units.get("ml") else Units.get("us_fl_oz")
                else -> packageUnit
            }
            packageQuantity = rounded(Units.convert(packageQuantity, packageUnit, target), target, policy)
            packageUnit = target
        }
        return IngredientDisplay(ingredient, quantity, unit, ingredient.unit, packageQuantity, packageUnit, approx, fractionCount, policy)
    }

    private fun metricVolume(ml: Double, preference: MetricVolume): FoodUnit = Units.get(when (preference) {
        MetricVolume.Millilitres -> "ml"; MetricVolume.Decilitres -> "dl"
        MetricVolume.Auto -> if (ml >= 1000) "l" else if (ml >= 100) "dl" else "ml"
    })
    private fun usVolume(ml: Double): FoodUnit = Units.get(if (ml >= Units.get("us_cup").factor) "us_cup" else if (ml >= Units.get("us_tbsp").factor) "us_tbsp" else "us_tsp")
    fun rounded(quantity: Quantity, unit: FoodUnit, policy: FoodPolicy): Quantity {
        if (policy.rounding == FoodRounding.Exact) return quantity
        val step = if (policy.rounding == FoodRounding.Custom) policy.customStep.also { require(it.isFinite() && it > 0) }
        else when (unit.id) {
            "kg", "l" -> 0.01
            "dl" -> 0.1
            "g", "ml" -> if (quantity.max >= 100) 5.0 else if (quantity.max >= 10) 1.0 else 0.1
            "us_cup", "us_tbsp", "us_tsp" -> return quantity // fraction formatting chooses kitchen fractions
            else -> 0.01
        }
        fun one(value: Double) = if (value == 0.0) 0.0 else max(step, round(value / step) * step).let { if (value < step / 2) value else it }
        return Quantity(one(quantity.min), one(quantity.max))
    }

    fun scaleFromIngredient(ingredient: Ingredient, amount: Quantity, unit: String?): Double {
        val original = requireNotNull(ingredient.quantity)
        require(ingredient.scalable && original.min == original.max && original.min > 0 && amount.min == amount.max && amount.min > 0) { "Choose an exact scalable ingredient" }
        val from = Units.find(unit); val to = Units.find(ingredient.unit)
        val available = if (from != null && to != null) Units.convert(amount.min, from, to) else {
            require(unit == ingredient.unit) { "Incompatible ingredient units" }; amount.min
        }
        return available / original.min
    }

    fun stepText(text: String, policy: FoodPolicy): String {
        if (policy.target == UnitMode.Original) return text
        var result = text
        if (policy.target == UnitMode.Metric) {
            result = Regex("""(-?\d+(?:[.,]\d+)?)\s*(?:°\s*F\b|degrees?\s+Fahrenheit\b)""", RegexOption.IGNORE_CASE).replace(result) {
                val f = it.groupValues[1].replace(',', '.').toDouble()
                QuantityFormatter.decimal(round(((f - 32) * 5 / 9) / 5) * 5, policy.decimalComma) + "°C"
            }
            result = Regex("""(\d+(?:[.,]\d+)?)\s*(?:inches\b|inch\b|[″"])""", RegexOption.IGNORE_CASE).replace(result) {
                QuantityFormatter.decimal(round(it.groupValues[1].replace(',', '.').toDouble() * 2.54), policy.decimalComma) + " cm"
            }
            val gas = mapOf("1/4" to 110, "¼" to 110, "1/2" to 120, "½" to 120, "1" to 140, "2" to 150, "3" to 160, "4" to 180, "5" to 190, "6" to 200, "7" to 220, "8" to 230, "9" to 240)
            result = Regex("""gas\s*(?:mark\s*)?(1/4|1/2|¼|½|[1-9])\b""", RegexOption.IGNORE_CASE).replace(result) { "${gas.getValue(it.groupValues[1])}°C" }
        } else {
            result = Regex("""(-?\d+(?:[.,]\d+)?)\s*°\s*C\b""", RegexOption.IGNORE_CASE).replace(result) {
                QuantityFormatter.decimal(round((it.groupValues[1].replace(',', '.').toDouble() * 9 / 5 + 32) / 25) * 25) + "°F"
            }
            result = Regex("""(\d+(?:[.,]\d+)?)\s*cm\b""", RegexOption.IGNORE_CASE).replace(result) {
                QuantityFormatter.fraction(it.groupValues[1].replace(',', '.').toDouble() / 2.54, true) + " in"
            }
        }
        return result
    }
}
