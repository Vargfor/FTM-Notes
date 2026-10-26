package io.github.vargfor.ftmnotes.core.food.engine

import io.github.vargfor.ftmnotes.core.model.Quantity

enum class Dimension { Volume, Mass, Count, Length, Temperature }
enum class UnitSystem { Metric, US, UK }
data class FoodUnit(val id: String, val symbol: String, val dimension: Dimension, val system: UnitSystem,
    val factor: Double, val aliases: List<String> = emptyList(), val offset: Double = 0.0)

/** Exact US customary factors; UK and metric cups/spoons are distinct, never silently substituted. */
object Units {
    val all = listOf(
        FoodUnit("ml", "ml", Dimension.Volume, UnitSystem.Metric, 1.0, listOf("milliliter", "milliliters", "millilitre", "millilitres")),
        FoodUnit("cl", "cl", Dimension.Volume, UnitSystem.Metric, 10.0, listOf("centiliter", "centiliters", "centilitre")),
        FoodUnit("dl", "dl", Dimension.Volume, UnitSystem.Metric, 100.0, listOf("deciliter", "deciliters", "desiliter")),
        FoodUnit("l", "l", Dimension.Volume, UnitSystem.Metric, 1000.0, listOf("liter", "liters", "litre", "litres")),
        FoodUnit("us_tsp", "tsp", Dimension.Volume, UnitSystem.US, 4.92892159375, listOf("tsp", "teaspoon", "teaspoons", "t")),
        FoodUnit("us_tbsp", "tbsp", Dimension.Volume, UnitSystem.US, 14.78676478125, listOf("tbsp", "tbs", "tablespoon", "tablespoons")),
        FoodUnit("metric_tsp", "tsp", Dimension.Volume, UnitSystem.Metric, 5.0, listOf("ts", "tsk", "TL", "teskje", "teskjeer", "metric teaspoon")),
        FoodUnit("metric_tbsp", "tbsp", Dimension.Volume, UnitSystem.Metric, 15.0, listOf("ss", "msk", "EL", "spiseskje", "spiseskjeer", "metric tablespoon")),
        FoodUnit("au_tbsp", "tbsp (AU)", Dimension.Volume, UnitSystem.Metric, 20.0, listOf("australian tablespoon", "australian tbsp")),
        FoodUnit("us_cup", "cup", Dimension.Volume, UnitSystem.US, 236.5882365, listOf("cup", "cups", "c", "us cup")),
        FoodUnit("metric_cup", "cup (metric)", Dimension.Volume, UnitSystem.Metric, 250.0, listOf("metric cup", "australian cup")),
        FoodUnit("uk_cup", "cup (UK)", Dimension.Volume, UnitSystem.UK, 284.130625, listOf("imperial cup", "uk cup")),
        FoodUnit("us_fl_oz", "fl oz", Dimension.Volume, UnitSystem.US, 29.5735295625, listOf("fl oz", "fluid ounce", "fluid ounces", "fl. oz")),
        FoodUnit("uk_fl_oz", "fl oz (UK)", Dimension.Volume, UnitSystem.UK, 28.4130625, listOf("uk fl oz", "imperial fluid ounce")),
        FoodUnit("us_pint", "pt", Dimension.Volume, UnitSystem.US, 473.176473, listOf("pt", "pint", "pints", "us pint")),
        FoodUnit("uk_pint", "pt (UK)", Dimension.Volume, UnitSystem.UK, 568.26125, listOf("uk pint", "imperial pint")),
        FoodUnit("us_quart", "qt", Dimension.Volume, UnitSystem.US, 946.352946, listOf("qt", "quart", "quarts")),
        FoodUnit("us_gallon", "gal", Dimension.Volume, UnitSystem.US, 3785.411784, listOf("gal", "gallon", "gallons")),
        FoodUnit("g", "g", Dimension.Mass, UnitSystem.Metric, 1.0, listOf("gram", "grams", "gramme", "grammes", "gramm")),
        FoodUnit("kg", "kg", Dimension.Mass, UnitSystem.Metric, 1000.0, listOf("kilogram", "kilograms", "kilo")),
        FoodUnit("mg", "mg", Dimension.Mass, UnitSystem.Metric, 0.001, listOf("milligram", "milligrams")),
        FoodUnit("oz", "oz", Dimension.Mass, UnitSystem.US, 28.349523125, listOf("ounce", "ounces")),
        FoodUnit("lb", "lb", Dimension.Mass, UnitSystem.US, 453.59237, listOf("lbs", "pound", "pounds")),
        FoodUnit("mm", "mm", Dimension.Length, UnitSystem.Metric, 1.0, listOf("millimeter", "millimetre")),
        FoodUnit("cm", "cm", Dimension.Length, UnitSystem.Metric, 10.0, listOf("centimeter", "centimetre")),
        FoodUnit("inch", "in", Dimension.Length, UnitSystem.US, 25.4, listOf("in", "inch", "inches", "\"")),
        FoodUnit("celsius", "°C", Dimension.Temperature, UnitSystem.Metric, 1.0, listOf("°c", "celsius")),
        FoodUnit("fahrenheit", "°F", Dimension.Temperature, UnitSystem.US, 5.0 / 9, listOf("°f", "fahrenheit"), -32.0 * 5 / 9),
        FoodUnit("piece", "", Dimension.Count, UnitSystem.Metric, 1.0, listOf("piece", "pieces", "pcs", "stk", "st", "stykk", "stykker")),
        FoodUnit("clove", "clove", Dimension.Count, UnitSystem.Metric, 1.0, listOf("clove", "cloves", "fedd")),
        FoodUnit("can", "can", Dimension.Count, UnitSystem.Metric, 1.0, listOf("can", "cans", "tin", "tins", "boks", "bokser")),
        FoodUnit("package", "package", Dimension.Count, UnitSystem.Metric, 1.0, listOf("package", "packages", "packet", "packets", "pakke", "pakker")),
        FoodUnit("bunch", "bunch", Dimension.Count, UnitSystem.Metric, 1.0, listOf("bunch", "bunches", "bunt")),
        FoodUnit("slice", "slice", Dimension.Count, UnitSystem.Metric, 1.0, listOf("slice", "slices", "skive", "skiver")),
        FoodUnit("pinch", "pinch", Dimension.Count, UnitSystem.Metric, 1.0, listOf("pinch", "pinches", "klype")),
        FoodUnit("stick", "stick", Dimension.Count, UnitSystem.US, 1.0, listOf("stick", "sticks")),
    )
    private val ids = all.associateBy { it.id }
    private val aliases = all.flatMap { unit -> (unit.aliases + unit.id).map { it.lowercase().trimEnd('.') to unit } }.toMap()
    private val prefixes = all.flatMap { unit -> (unit.aliases + unit.id).map { it to unit } }.sortedByDescending { it.first.length }
    fun find(text: String?): FoodUnit? {
        if (text == null) return null
        if (text.trim().trimEnd('.') == "T") return ids.getValue("us_tbsp")
        return aliases[text.trim().lowercase().trimEnd('.')]
    }
    fun get(id: String): FoodUnit = ids.getValue(id)
    data class Match(val unit: FoodUnit, val rest: String)
    fun prefix(text: String): Match? {
        if (text.startsWith("T") && (text.length == 1 || !text[1].isLetterOrDigit())) return Match(get("us_tbsp"), text.drop(1).trimStart('.', ' ', '\t'))
        for ((alias, unit) in prefixes) {
            if (text.startsWith(alias, ignoreCase = true) && (text.length == alias.length || !text[alias.length].isLetterOrDigit())) {
                return Match(unit, text.drop(alias.length).trimStart('.', ' '))
            }
        }
        return null
    }
    fun compatible(a: FoodUnit, b: FoodUnit): Boolean = a.dimension == b.dimension && (a.dimension != Dimension.Count || a.id == b.id)
    fun convert(value: Double, from: FoodUnit, to: FoodUnit): Double {
        require(compatible(from, to)) { "Incompatible units" }
        return (value * from.factor + from.offset - to.offset) / to.factor
    }
    fun convert(quantity: Quantity, from: FoodUnit, to: FoodUnit) = Quantity(convert(quantity.min, from, to), convert(quantity.max, from, to))
}
