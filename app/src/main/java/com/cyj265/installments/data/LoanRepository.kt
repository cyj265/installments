package com.cyj265.installments.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

interface LoanRepository {
    suspend fun load(): List<Loan>
    suspend fun save(loans: List<Loan>)
    suspend fun put(loan: Loan)
    suspend fun remove(id: String)
}

@Serializable
internal data class StoreFile(
    val schemaVersion: Int = 1,
    val savedAt: String = "",
    val loans: List<Loan> = emptyList(),
)

/**
 * 单一 JSON 文件落在应用私有目录，不用数据库：数据量是个位数十笔的量级，
 * 而可读了、可直接备份、出错能手工改这三点对自用工具比查询能力重要。
 */
class JsonLoanStore(private val file: File) : LoanRepository {

    /** 界面要把数据文件位置显示给用户，路径只在这里说一次，避免和构造处各写一份。 */
    val filePath: String get() = file.absolutePath

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()
    private var cache: List<Loan>? = null

    override suspend fun load(): List<Loan> = withContext(Dispatchers.IO) {
        mutex.withLock { loadLocked() }
    }

    override suspend fun save(loans: List<Loan>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            write(StoreFile(savedAt = Instant.now().toString(), loans = loans))
            cache = loans
        }
    }

    override suspend fun put(loan: Loan) {
        val current = load().toMutableList()
        val i = current.indexOfFirst { it.id == loan.id }
        if (i >= 0) current[i] = loan else current += loan
        save(current)
    }

    override suspend fun remove(id: String) {
        save(load().filterNot { it.id == id })
    }

    private suspend fun loadLocked(): List<Loan> {
        cache?.let { return it }
        val loans = if (!file.exists()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString<StoreFile>(file.readText()).loans }.getOrElse {
                // 文件被手工改坏时不能静默当成空库，否则用户会以为记录丢了。
                file.renameTo(File(file.parentFile, file.name + ".corrupt-${Instant.now().epochSecond}"))
                emptyList()
            }
        }
        cache = loans
        return loans
    }

    private fun write(store: StoreFile) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(StoreFile.serializer(), store))
        if (file.exists() && !file.delete()) error("无法覆盖旧数据文件：${file.absolutePath}")
        if (!tmp.renameTo(file)) error("数据文件写入失败：${file.absolutePath}")
    }
}
