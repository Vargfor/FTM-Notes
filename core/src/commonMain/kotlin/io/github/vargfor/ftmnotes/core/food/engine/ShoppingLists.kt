package io.github.vargfor.ftmnotes.core.food.engine

import io.github.vargfor.ftmnotes.core.model.*

object ShoppingLists {
    fun undoMutation(before: ShoppingListDocument, applied: ShoppingListDocument, current: ShoppingListDocument): ShoppingListDocument {
        val old = before.items.associateBy { it.id }; val written = applied.items.associateBy { it.id }
        val result = current.items.mapNotNull { item ->
            val after = written[item.id]
            val previous = old[item.id]
            if (previous == null && after != null) return@mapNotNull if (item.copy(modifiedHlc = null) == after.copy(modifiedHlc = null)) null else item
            if (previous == null) return@mapNotNull item
            if (after == null) return@mapNotNull if (item.deleted) previous else item
            item.copy(text = if (item.text == after.text) previous.text else item.text,
                quantity = if (item.quantity == after.quantity) previous.quantity else item.quantity,
                unit = if (item.unit == after.unit) previous.unit else item.unit,
                category = if (item.category == after.category) previous.category else item.category,
                checked = if (item.checked == after.checked) previous.checked else item.checked,
                sources = if (item.sources == after.sources) previous.sources else item.sources,
                deleted = if (item.deleted == after.deleted) previous.deleted else item.deleted)
        }.toMutableList()
        val ids = result.map { it.id }.toSet()
        before.items.filter { it.id !in ids && it.id !in written }.forEach { result += it }
        return current.copy(items = result, categoryOrder = if (current.categoryOrder == applied.categoryOrder) before.categoryOrder else current.categoryOrder)
    }
    /** Reverse our contribution without replacing unrelated items or later field edits. */
    fun undoAdd(before: List<ShoppingItem>, applied: List<ShoppingItem>, current: List<ShoppingItem>): List<ShoppingItem> {
        val old = before.associateBy { it.id }; val changed = applied.associateBy { it.id }
        return current.mapNotNull { item ->
            val after = changed[item.id] ?: return@mapNotNull item
            val previous = old[item.id]
            if (previous == null) return@mapNotNull if (item.copy(modifiedHlc = null) == after.copy(modifiedHlc = null)) null else item
            if (previous == after || previous.quantity == null || after.quantity == null || item.quantity == null || item.unit != after.unit) return@mapNotNull item
            val low = item.quantity.min - (after.quantity.min - previous.quantity.min)
            val high = item.quantity.max - (after.quantity.max - previous.quantity.max)
            if (low < 0 || high < low) item else item.copy(quantity = Quantity(low, high), sources = item.sources - (after.sources - previous.sources).toSet())
        }
    }
    fun fromRecipe(recipe: RecipeDocument, factor: Double, ingredientIds: Set<String>? = null): List<ShoppingItem> =
        recipe.ingredientGroups.flatMap { it.items }.filter { ingredientIds == null || it.id in ingredientIds }.map { ingredient ->
            ShoppingItem(id = Ids.newId(), text = ingredient.name.ifBlank { ingredient.raw },
                quantity = ingredient.quantity?.let { if (ingredient.scalable) it.scaled(factor) else it },
                unit = ingredient.unit, category = ingredient.category.ifBlank { Categories.suggest(ingredient.name) },
                sources = recipe.id?.let { listOf(ShoppingSource(it, ingredient.id)) }.orEmpty())
        }

    fun quickAdd(text: String): ShoppingItem {
        val parsed = IngredientParser.parse(text)
        return ShoppingItem(Ids.newId(), parsed.name, parsed.quantity, parsed.unit, parsed.category)
    }

    /** Only unchecked, quantified items with matching names and compatible units are summed. */
    fun add(existing: List<ShoppingItem>, additions: List<ShoppingItem>): List<ShoppingItem> {
        val result = existing.toMutableList()
        additions.forEach { addition ->
            val from = Units.find(addition.unit)
            val index = result.indexOfFirst { item ->
                !item.checked && !item.deleted && normalName(item.text) == normalName(addition.text) &&
                    item.quantity != null && addition.quantity != null &&
                    ((item.unit == null && addition.unit == null) || (from != null && Units.find(item.unit)?.let { Units.compatible(from, it) } == true) ||
                        (from == null && item.unit != null && item.unit == addition.unit))
            }
            if (index < 0) result += addition.copy(id = addition.id ?: Ids.newId())
            else {
                val item = result[index]
                val to = Units.find(item.unit)
                val quantity = if (from != null && to != null) Units.convert(addition.quantity!!, from, to) else addition.quantity!!
                result[index] = item.copy(quantity = item.quantity!! + quantity, sources = (item.sources + addition.sources).distinct())
            }
        }
        return result
    }
    private fun normalName(text: String) = text.trim().lowercase().replace(Regex("\\s+"), " ")
}

fun RecipeDocument.normalisedContent(): RecipeDocument = copy(
    ingredientGroups = ingredientGroups.map { group -> group.copy(id = group.id ?: Ids.newId(), items = group.items.map { ingredient ->
        val parsed = if (ingredient.name.isBlank() && ingredient.raw.isNotBlank()) IngredientParser.parse(ingredient.raw) else ingredient
        ingredient.copy(id = ingredient.id ?: Ids.newId(), name = ingredient.name.ifBlank { parsed.name },
            quantity = ingredient.quantity ?: parsed.quantity, unit = Units.find(ingredient.unit ?: parsed.unit)?.id ?: ingredient.unit ?: parsed.unit,
            note = ingredient.note.ifBlank { parsed.note }, category = ingredient.category.ifBlank { parsed.category },
            scalable = ingredient.scalable && parsed.scalable, packageQuantity = ingredient.packageQuantity ?: parsed.packageQuantity,
            packageUnit = Units.find(ingredient.packageUnit ?: parsed.packageUnit)?.id ?: ingredient.packageUnit ?: parsed.packageUnit)
    }) },
    steps = steps.map { it.copy(id = it.id ?: Ids.newId()) },
)

fun stampShoppingEdits(before: ShoppingListDocument?, edited: ShoppingListDocument, hlc: String): ShoppingListDocument {
    val old = before?.items.orEmpty().associateBy { it.id }
    val oldActive = before?.items.orEmpty().filterNot { it.deleted }
    val ids = edited.items.map { it.id }.toSet()
    val items = edited.items.mapIndexed { index, item ->
        val previous = old[item.id]
        if (previous != null && previous.copy(modifiedHlc = null) == item.copy(modifiedHlc = null) &&
            (item.deleted || oldActive.indexOfFirst { it.id == item.id } == index)) previous.copy(modifiedHlc = previous.modifiedHlc ?: hlc)
        else item.copy(modifiedHlc = hlc)
    } + old.values.filter { it.id !in ids }.map { if (it.deleted) it else it.copy(deleted = true, modifiedHlc = hlc) }
    return edited.copy(items = items)
}
