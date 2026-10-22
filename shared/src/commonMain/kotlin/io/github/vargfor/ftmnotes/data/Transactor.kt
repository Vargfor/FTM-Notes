package io.github.vargfor.ftmnotes.data

import androidx.room3.withWriteTransaction
import io.github.vargfor.ftmnotes.data.db.AppDatabase

/** Runs a block in one write transaction; DAO calls inside reuse the transaction's connection. */
class Transactor(private val db: AppDatabase) {
    suspend fun <R> write(block: suspend () -> R): R = db.withWriteTransaction { block() }
}
