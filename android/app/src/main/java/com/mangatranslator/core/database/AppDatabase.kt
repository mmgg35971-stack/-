package com.mangatranslator.core.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Local "project" storage so an unfinished chapter survives app restarts
 * (spec: project saving). Server state (plans, usage, jobs) is never cached
 * here as truth.
 */
@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey val id: String,              // also used as the server idempotency key
    val chapterTitle: String,
    val chapterNumber: Double?,
    val workId: String?,
    val quality: String,
    val createdAt: Long,
    val state: String,                       // editing | submitting | submitted | failed
    val serverJobId: String? = null,
    val errorCode: String? = null,
)

@Entity(tableName = "draft_pages", primaryKeys = ["draftId", "position"])
data class DraftPageEntity(
    val draftId: String,
    val position: Int,
    val localPath: String,                   // private copy in filesDir/drafts/<id>/
    val displayName: String,
    val mimeType: String,
    val byteSize: Long,
    val uploaded: Boolean = false,
)

@Dao
interface DraftDao {
    @Query("SELECT * FROM drafts WHERE state != 'submitted' ORDER BY createdAt DESC")
    fun openDrafts(): Flow<List<DraftEntity>>

    @Query("SELECT * FROM drafts WHERE id = :id")
    suspend fun draft(id: String): DraftEntity?

    @Query("SELECT * FROM drafts WHERE id = :id")
    fun observeDraft(id: String): Flow<DraftEntity?>

    @Query("SELECT * FROM draft_pages WHERE draftId = :id ORDER BY position")
    suspend fun pages(id: String): List<DraftPageEntity>

    @Query("SELECT * FROM draft_pages WHERE draftId = :id ORDER BY position")
    fun observePages(id: String): Flow<List<DraftPageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDraft(d: DraftEntity)

    @Update
    suspend fun updateDraft(d: DraftEntity)

    @Query("UPDATE draft_pages SET uploaded = 1 WHERE draftId = :id AND position = :position")
    suspend fun markUploaded(id: String, position: Int)

    @Query("DELETE FROM draft_pages WHERE draftId = :id")
    suspend fun deletePages(id: String)

    @Insert
    suspend fun insertPages(p: List<DraftPageEntity>)

    @Transaction
    suspend fun replacePages(id: String, pages: List<DraftPageEntity>) {
        deletePages(id)
        insertPages(pages)
    }

    @Query("DELETE FROM drafts WHERE id = :id")
    suspend fun deleteDraft(id: String)
}

@Database(entities = [DraftEntity::class, DraftPageEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun draftDao(): DraftDao
}
