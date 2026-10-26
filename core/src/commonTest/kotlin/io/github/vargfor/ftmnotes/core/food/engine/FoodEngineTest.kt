package io.github.vargfor.ftmnotes.core.food.engine

import io.github.vargfor.ftmnotes.core.model.*
import io.github.vargfor.ftmnotes.core.format.*
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.*

class FoodEngineTest {
    @Test fun ingredientCorpusCoversMoreThanTwoHundredCases() {
        val numbers = listOf("1" to 1.0, "2" to 2.0, "0.5" to 0.5, "0,5" to 0.5, "½" to 0.5,
            "¼" to 0.25, "¾" to 0.75, "⅓" to 1.0/3, "⅔" to 2.0/3, "⅛" to 0.125,
            "1 1/2" to 1.5, "1½" to 1.5, "2 3/4" to 2.75, "2¾" to 2.75, "3,5" to 3.5,
            "1/4" to 0.25, "3/8" to 0.375, ".5" to 0.5)
        val measures = listOf("g" to "g", "grams" to "g", "kg" to "kg", "ml" to "ml", "dl" to "dl",
            "l" to "l", "cups" to "us_cup", "tbsp" to "us_tbsp", "T" to "us_tbsp", "t" to "us_tsp",
            "ss" to "metric_tbsp", "tsk" to "metric_tsp", "oz" to "oz", "lb" to "lb")
        var cases = 0
        for ((number, expected) in numbers) for ((alias, unit) in measures) {
            val raw = "$number $alias flour, sifted"
            val parsed = IngredientParser.parse(raw)
            assertEquals(expected, assertNotNull(parsed.quantity, raw).min, 1e-10, raw)
            assertEquals(unit, parsed.unit, raw)
            assertEquals("flour", parsed.name, raw)
            assertEquals("sifted", parsed.note, raw)
            assertEquals(raw, parsed.raw)
            cases++
        }
        assertTrue(cases >= 200)
    }

    @Test fun rangesPackagesAndNonScalableTextArePreserved() {
        listOf("2-3 eggs", "2 to 3 eggs", "2–3 eggs", "2 til 3 eggs").forEach { input ->
            val parsed = IngredientParser.parse(input)
            assertEquals(Quantity(2.0, 3.0), parsed.quantity, input)
            assertEquals("eggs", parsed.name)
        }
        val can = IngredientParser.parse("1 (14 oz) can tomatoes")
        assertEquals(Quantity(1.0), can.quantity); assertEquals("can", can.unit)
        assertEquals(Quantity(14.0), can.packageQuantity); assertEquals("oz", can.packageUnit)
        assertEquals("tomatoes", can.name)
        val butter = IngredientParser.parse("stick of butter")
        assertEquals("stick", butter.unit); assertEquals(Quantity(113.3980925), butter.packageQuantity)
        assertFalse(IngredientParser.parse("salt to taste").scalable)
        assertFalse(IngredientParser.parse("pepper, etter smak").scalable)
        assertTrue(IngredientParser.parse("2 eggs (optional)").optional)
        assertEquals("parsley", IngredientParser.parse("parsley").name)
        assertEquals("1/0 cups sugar", IngredientParser.parse("1/0 cups sugar").raw)
        assertNull(AmountParser.parse("3-2"))
    }

    @Test fun conversionsUseDistinctMassVolumeAndRegionalMeasures() {
        data class Case(val value: Double, val from: String, val to: String, val expected: Double)
        val cases = listOf(
            Case(1.0,"us_cup","ml",236.5882365), Case(1.0,"metric_cup","ml",250.0),
            Case(1.0,"uk_cup","ml",284.130625), Case(1.0,"us_tbsp","ml",14.78676478125),
            Case(1.0,"metric_tbsp","ml",15.0), Case(1.0,"au_tbsp","ml",20.0),
            Case(1.0,"us_tsp","ml",4.92892159375), Case(1.0,"metric_tsp","ml",5.0),
            Case(1.0,"lb","g",453.59237), Case(1.0,"oz","g",28.349523125),
            Case(2.0,"l","dl",20.0), Case(1.0,"inch","cm",2.54),
            Case(212.0,"fahrenheit","celsius",100.0), Case(32.0,"fahrenheit","celsius",0.0))
        cases.forEach { assertEquals(it.expected, Units.convert(it.value, Units.get(it.from), Units.get(it.to)), 1e-9, it.toString()) }
        assertFailsWith<IllegalArgumentException> { Units.convert(1.0, Units.get("g"), Units.get("ml")) }
        assertFalse(Units.compatible(Units.get("can"), Units.get("slice")))
        assertEquals("us_tbsp", Units.find("T")!!.id); assertEquals("us_tsp", Units.find("t")!!.id)
    }

    @Test fun conversionAndScalingPropertiesHoldWithoutMutatingStoredRecipes() {
        val random = Random(739)
        val units = Units.all.filter { it.dimension != Dimension.Temperature && it.dimension != Dimension.Count }
        repeat(200) {
            val source = units.random(random)
            val target = units.filter { it.dimension == source.dimension }.random(random)
            val value = random.nextDouble(0.01, 10000.0)
            val converted = Units.convert(value, source, target)
            assertEquals(value, Units.convert(converted, target, source), value * 1e-12)
            val factor = random.nextDouble(0.05, 20.0)
            val q = Quantity(value, value * 1.5)
            val back = q.scaled(factor).scaled(1 / factor)
            assertEquals(q.min, back.min, value * 1e-12)
            assertEquals(q.max, back.max, value * 1e-12)
        }
        val recipe = RecipeDocument(title = "Pancakes", servings = Servings(4.0),
            ingredientGroups = listOf(IngredientGroup(items = listOf(IngredientParser.parse("2 cups flour"), IngredientParser.parse("salt to taste")))),
            steps = listOf(RecipeStep(text = "Bake at 350°F for 20 minutes")))
        val canonical = FtmFormat.encodeDocument(recipe)
        for (target in UnitMode.entries) for (factor in listOf(0.5, 2.0, 3.0)) {
            recipe.ingredientGroups.flatMap { it.items }.forEach { FoodEngine.display(it, factor, FoodPolicy(target)) }
            recipe.steps.forEach { FoodEngine.stepText(it.text, FoodPolicy(target)) }
        }
        assertEquals(canonical, FtmFormat.encodeDocument(recipe))
        assertEquals(recipe, FtmReader.read(FtmFormat.write(listOf(recipe))).documents.single())
    }

    @Test fun displayKeepsSpoonsMarksDensitiesAndLeavesUnknownIngredientsAlone() {
        assertEquals("us_tbsp", FoodEngine.display(IngredientParser.parse("1 tbsp sugar")).unit!!.id)
        val flour = FoodEngine.display(IngredientParser.parse("1 cup flour"), policy = FoodPolicy(rounding = FoodRounding.Exact))
        assertTrue(flour.approximate)
        assertEquals(120.0, flour.quantity!!.min, 1e-8)
        assertEquals("g", flour.unit!!.id)
        val unknown = FoodEngine.display(IngredientParser.parse("1 cup mystery powder"), policy = FoodPolicy(rounding = FoodRounding.Exact))
        assertFalse(unknown.approximate); assertEquals("dl", unknown.unit!!.id)
        val fixed = IngredientParser.parse("salt to taste")
        assertEquals(fixed.raw, FoodEngine.display(fixed, 3.0).text())
        val eggs = FoodEngine.display(IngredientParser.parse("1 egg"), 1.5)
        assertTrue(eggs.fractionalCount); assertEquals(1.5, eggs.quantity!!.min)
        val overridden = FoodEngine.display(IngredientParser.parse("1 cup flour"), policy = FoodPolicy(densities = mapOf("flour" to 1.0), rounding = FoodRounding.Exact))
        assertEquals(236.5882365, overridden.quantity!!.min, 1e-9)
    }

    @Test fun formatsFractionsDecimalsAndRangeQuantities() {
        assertEquals("1 1/2", QuantityFormatter.format(Quantity(1.5), us = true))
        assertEquals("1/3", QuantityFormatter.format(Quantity(1.0/3), us = true))
        assertEquals("1,5", QuantityFormatter.format(Quantity(1.5), comma = true))
        assertEquals("2–3", QuantityFormatter.format(Quantity(2.0, 3.0)))
        assertEquals("-17.5", QuantityFormatter.decimal(-17.5))
        assertEquals("Bake at 175°C in a 23 cm pan.", FoodEngine.stepText("Bake at 350°F in a 9 inch pan.", FoodPolicy()))
        assertEquals("Bake at 350°F", FoodEngine.stepText("Bake at 175°C", FoodPolicy(UnitMode.US)))
        assertEquals("Gas mark 4", FoodEngine.stepText("Gas mark 4", FoodPolicy(UnitMode.Original)))
        assertEquals("180°C", FoodEngine.stepText("Gas mark 4", FoodPolicy()))
    }

    @Test fun scaleByIngredientConvertsCompatibleAvailableQuantities() {
        val chicken = IngredientParser.parse("500 g chicken")
        assertEquals(0.6, FoodEngine.scaleFromIngredient(chicken, Quantity(0.3), "kg"), 1e-9)
        assertFailsWith<IllegalArgumentException> { FoodEngine.scaleFromIngredient(chicken, Quantity(300.0), "ml") }
        assertFailsWith<IllegalArgumentException> { FoodEngine.factor(RecipeDocument(), 0.0) }
    }

    @Test fun shoppingSummingIsUnitAwareAndRetainsRecipeLinks() {
        val old = ShoppingItem("a", "Milk", Quantity(1.0), "l", sources = listOf(ShoppingSource("first")))
        val added = ShoppingItem("b", "milk", Quantity(500.0), "ml", sources = listOf(ShoppingSource("second")))
        val result = ShoppingLists.add(listOf(old), listOf(added))
        assertEquals(1, result.size); assertEquals(Quantity(1.5), result.single().quantity)
        assertEquals(listOf("first", "second"), result.single().sources.map { it.recipeId })
        assertEquals(2, ShoppingLists.add(listOf(old.copy(checked = true)), listOf(added)).size)
        assertEquals(2, ShoppingLists.add(listOf(old.copy(unit = "g")), listOf(added)).size)
        assertEquals(2, ShoppingLists.add(listOf(old.copy(quantity = null)), listOf(added)).size)
    }

    @Test fun readerAcceptsHumanQuantitiesAndUnitAliases() {
        val read = FtmReader.read("""{"type":"recipe","title":"Bread","servings":"4 portions","ingredients":["1½ cups flour",{"quantity":"2-3","unit":"Tablespoons","name":"water"}],"instructions":["Mix","Bake"]}""")
        assertTrue(read.errors.isEmpty(), read.issues.toString())
        val recipe = read.documents.single() as RecipeDocument
        assertEquals(4.0, recipe.servings.amount)
        assertEquals(Quantity(1.5), recipe.ingredientGroups.single().items.first().quantity)
        assertEquals("us_tbsp", recipe.ingredientGroups.single().items.last().unit)
        assertEquals(listOf("Mix","Bake"), recipe.steps.map { it.text })
        assertTrue(read.issues.isNotEmpty())
    }
}
