package com.daedalus.notes.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.daedalus.notes.data.model.TodoItem
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoDao {

    @Query("SELECT * FROM todos ORDER BY isDone ASC, createdAt DESC")
    fun getAllFlow(): Flow<List<TodoItem>>

    @Query("SELECT * FROM todos ORDER BY isDone ASC, createdAt DESC")
    suspend fun getAll(): List<TodoItem>

    @Insert
    suspend fun insert(item: TodoItem): Long

    @Insert
    suspend fun insertAll(items: List<TodoItem>)

    @Update
    suspend fun update(item: TodoItem)

    @Delete
    suspend fun delete(item: TodoItem)

    @Query("UPDATE todos SET isDone = :done WHERE id = :id")
    suspend fun setDone(id: Long, done: Boolean)
}
