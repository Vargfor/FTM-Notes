package io.github.vargfor.ftmnotes.core.food.engine

import io.github.vargfor.ftmnotes.core.model.RecipeDocument

/** Merge this editor's fields onto the latest version so organisation changes aren't overwritten. */
fun mergeRecipeEdits(current: RecipeDocument, before: RecipeDocument, edited: RecipeDocument): RecipeDocument = current.copy(
    title = if (edited.title != before.title) edited.title else current.title,
    description = if (edited.description != before.description) edited.description else current.description,
    servings = if (edited.servings != before.servings) edited.servings else current.servings,
    prepMinutes = if (edited.prepMinutes != before.prepMinutes) edited.prepMinutes else current.prepMinutes,
    cookMinutes = if (edited.cookMinutes != before.cookMinutes) edited.cookMinutes else current.cookMinutes,
    totalMinutes = if (edited.totalMinutes != before.totalMinutes) edited.totalMinutes else current.totalMinutes,
    ingredientGroups = if (edited.ingredientGroups != before.ingredientGroups) edited.ingredientGroups else current.ingredientGroups,
    steps = if (edited.steps != before.steps) edited.steps else current.steps,
    notes = if (edited.notes != before.notes) edited.notes else current.notes,
    sourceUrl = if (edited.sourceUrl != before.sourceUrl) edited.sourceUrl else current.sourceUrl,
    sourceName = if (edited.sourceName != before.sourceName) edited.sourceName else current.sourceName,
    images = if (edited.images != before.images) edited.images else current.images,
    nutrition = if (edited.nutrition != before.nutrition) edited.nutrition else current.nutrition,
    rating = if (edited.rating != before.rating) edited.rating else current.rating,
    tags = if (edited.tags != before.tags) edited.tags else current.tags,
    pinned = if (edited.pinned != before.pinned) edited.pinned else current.pinned,
    archived = if (edited.archived != before.archived) edited.archived else current.archived,
    color = if (edited.color != before.color) edited.color else current.color,
)
