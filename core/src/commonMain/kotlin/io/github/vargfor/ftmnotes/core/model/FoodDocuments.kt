package io.github.vargfor.ftmnotes.core.model

import io.github.vargfor.ftmnotes.core.format.LenientInstantSerializer
import io.github.vargfor.ftmnotes.core.food.engine.QuantitySerializer
import kotlinx.serialization.*
import kotlin.time.Instant

@Serializable(with = QuantitySerializer::class)
data class Quantity(val min: Double, val max: Double = min) {
    init { require(min.isFinite() && max.isFinite() && min >= 0 && max >= min) { "Invalid quantity" } }
    fun scaled(factor: Double): Quantity {
        require(factor.isFinite() && factor > 0) { "Scale must be positive" }
        return Quantity(min * factor, max * factor)
    }
    operator fun plus(other: Quantity) = Quantity(min + other.min, max + other.max)
}

@Serializable
data class Servings(val amount: Double = 1.0, val label: String = "") {
    init { require(amount.isFinite() && amount > 0) { "Servings must be positive" } }
}
@Serializable
data class Ingredient(
    val id: String? = null, val raw: String = "", val quantity: Quantity? = null, val unit: String? = null,
    val name: String = "", val note: String = "", val optional: Boolean = false,
    val scalable: Boolean = true, val category: String = "",
    val packageQuantity: Quantity? = null, val packageUnit: String? = null,
)
@Serializable
data class IngredientGroup(val id: String? = null, val name: String? = null, val items: List<Ingredient> = emptyList())
@Serializable
data class RecipeStep(val id: String? = null, val text: String = "", val timerSeconds: Long? = null, val section: String? = null)

@Serializable
@SerialName("recipe")
data class RecipeDocument(
    override val id: String? = null, override val title: String = "",
    override val tags: List<String> = emptyList(), override val pinned: Boolean = false,
    override val archived: Boolean = false, override val color: String? = null,
    @Serializable(with = LenientInstantSerializer::class) override val createdAt: Instant? = null,
    @Serializable(with = LenientInstantSerializer::class) override val updatedAt: Instant? = null,
    val description: String = "", val servings: Servings = Servings(),
    val prepMinutes: Int? = null, val cookMinutes: Int? = null, val totalMinutes: Int? = null,
    val ingredientGroups: List<IngredientGroup> = emptyList(), val steps: List<RecipeStep> = emptyList(),
    val notes: String = "", val sourceUrl: String? = null, val sourceName: String? = null,
    val images: List<AttachmentRef> = emptyList(), val nutrition: Map<String, String> = emptyMap(),
    val rating: Int? = null,
) : FtmDocument { override val documentType: DocumentType get() = DocumentType.Recipe }

@Serializable
data class ShoppingSource(val recipeId: String, val ingredientId: String? = null)
@Serializable
data class ShoppingItem(
    val id: String? = null, val text: String = "", val quantity: Quantity? = null, val unit: String? = null,
    val category: String = "other", val checked: Boolean = false,
    val sources: List<ShoppingSource> = emptyList(), val modifiedHlc: String? = null, val deleted: Boolean = false,
)
@Serializable
@SerialName("shopping_list")
data class ShoppingListDocument(
    override val id: String? = null, override val title: String = "",
    override val tags: List<String> = emptyList(), override val pinned: Boolean = false,
    override val archived: Boolean = false, override val color: String? = null,
    @Serializable(with = LenientInstantSerializer::class) override val createdAt: Instant? = null,
    @Serializable(with = LenientInstantSerializer::class) override val updatedAt: Instant? = null,
    val items: List<ShoppingItem> = emptyList(), val categoryOrder: List<String> = emptyList(),
) : FtmDocument { override val documentType: DocumentType get() = DocumentType.ShoppingList }
