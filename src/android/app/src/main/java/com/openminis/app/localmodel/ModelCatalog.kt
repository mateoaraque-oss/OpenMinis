package com.openminis.app.localmodel

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.util.Locale

/**
 * [T-orion-model-catalog] The short list of models Orion offers to download.
 *
 * Why a hand-written catalog and not "search Hugging Face": the phone has to
 * *run* the model, not just fetch it. These five files are Q4_K_M GGUF builds
 * with a verified size, a working chat template and a parameter count that
 * fits a mid-range Android device. A live search would happily return a 14B
 * model and then the user would watch it fail to load.
 *
 * Every entry keeps the exact repository path it came from so a downloaded
 * file can be checked against its advertised size, and so the same list can
 * later grow without touching the downloader.
 */
data class CatalogModel(
    val id: String,
    val displayName: String,
    val vendor: String,
    val parameters: String,
    val quantization: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    /** Approximate device RAM (GiB) below which the model is a bad idea. */
    val minDeviceRamGb: Double,
    val tier: Tier,
) {
    enum class Tier { LIGHT, BALANCED, QUALITY }

    /** e.g. "1.5B · Q4_K_M · 1.0 GB". */
    fun summary(): String = "$parameters · $quantization · ${formatSize(sizeBytes)}"

    companion object {
        fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "?"
            val gb = bytes / 1_073_741_824.0
            if (gb >= 1.0) return String.format(Locale.US, "%.1f GB", gb)
            val mb = bytes / 1_048_576.0
            return String.format(Locale.US, "%.0f MB", mb)
        }
    }
}

/** [T-orion-model-catalog] What the phone can actually take. */
data class DeviceCapacity(
    val totalRamBytes: Long,
    val freeStorageBytes: Long,
) {
    val totalRamGb: Double get() = totalRamBytes / 1_073_741_824.0
    val freeStorageGb: Double get() = freeStorageBytes / 1_073_741_824.0
}

object ModelCatalog {

    /**
     * Verified against the repositories' `Content-Length`. Order is deliberate:
     * lightest first, so a truncated list still shows sensible options.
     */
    val all: List<CatalogModel> = listOf(
        CatalogModel(
            id = "llama32-1b",
            displayName = "Llama 3.2 1B",
            vendor = "Meta",
            parameters = "1B",
            quantization = "Q4_K_M",
            fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            url = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            sizeBytes = 807_694_464L,
            minDeviceRamGb = 4.0,
            tier = CatalogModel.Tier.LIGHT,
        ),
        CatalogModel(
            id = "gemma3-1b",
            displayName = "Gemma 3 1B",
            vendor = "Google",
            parameters = "1B",
            quantization = "Q4_K_M",
            fileName = "gemma-3-1b-it-Q4_K_M.gguf",
            url = "https://huggingface.co/unsloth/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf",
            sizeBytes = 806_058_272L,
            minDeviceRamGb = 4.0,
            tier = CatalogModel.Tier.LIGHT,
        ),
        CatalogModel(
            id = "qwen25-15b",
            displayName = "Qwen2.5 1.5B",
            vendor = "Alibaba",
            parameters = "1.5B",
            quantization = "Q4_K_M",
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sizeBytes = 1_117_320_736L,
            minDeviceRamGb = 5.5,
            tier = CatalogModel.Tier.BALANCED,
        ),
        CatalogModel(
            id = "llama32-3b",
            displayName = "Llama 3.2 3B",
            vendor = "Meta",
            parameters = "3B",
            quantization = "Q4_K_M",
            fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            url = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            sizeBytes = 2_019_377_696L,
            minDeviceRamGb = 7.5,
            tier = CatalogModel.Tier.QUALITY,
        ),
        CatalogModel(
            id = "qwen25-3b",
            displayName = "Qwen2.5 3B",
            vendor = "Alibaba",
            parameters = "3B",
            quantization = "Q4_K_M",
            fileName = "qwen2.5-3b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            sizeBytes = 2_104_932_768L,
            minDeviceRamGb = 7.5,
            tier = CatalogModel.Tier.QUALITY,
        ),
    )

    /**
     * The model Orion suggests for *this* phone.
     *
     * Ranking is two hard constraints plus one preference:
     *  1. the weights must fit in RAM with room for the context and the app
     *     itself (we require roughly the file size plus 1.5 GB);
     *  2. the file must fit on disk with a 1 GB margin;
     *  3. among the survivors, the best tier the RAM allows wins.
     *
     * On a 8 GB phone (≈7.4 GiB reported) this lands on Qwen2.5 1.5B: small
     * enough to load in 10-20 s and noticeably better at tool use than the 1B
     * models. On 12 GB it picks a 3B. On 4 GB it falls back to Llama 1B.
     */
    fun recommended(context: Context): CatalogModel {
        val capacity = capacity(context)
        val ramGb = capacity.totalRamGb
        val free = capacity.freeStorageBytes

        val fits = all.filter { model ->
            ramGb + 0.001 >= model.minDeviceRamGb &&
                free > model.sizeBytes + 1_000_000_000L
        }
        if (fits.isNotEmpty()) {
            return fits.maxByOrNull { model ->
                val tierScore = when (model.tier) {
                    CatalogModel.Tier.QUALITY -> 3
                    CatalogModel.Tier.BALANCED -> 2
                    CatalogModel.Tier.LIGHT -> 1
                }
                // Prefer the top tier that still leaves headroom: a 3B on a
                // 7.5 GB device leaves less than a gigabyte for the context.
                val headroomPenalty = if (ramGb < model.minDeviceRamGb + 1.0) 1 else 0
                tierScore * 10 - headroomPenalty
            } ?: fits.first()
        }
        // Nothing measured fits — storage may be nearly full or the RAM query
        // failed (it can on some ROMs). The smallest file is the honest answer.
        return all.minByOrNull { it.sizeBytes } ?: all.first()
    }

    /** Everything else worth showing, best first, filtered by what fits. */
    fun alternatives(context: Context, chosen: CatalogModel): List<CatalogModel> {
        val capacity = capacity(context)
        val fits = all.filter {
            it.id != chosen.id &&
                capacity.totalRamGb + 0.001 >= it.minDeviceRamGb &&
                capacity.freeStorageBytes > it.sizeBytes + 500_000_000L
        }
        return fits.sortedBy { it.sizeBytes }
    }

    fun capacity(context: Context): DeviceCapacity {
        val totalRam = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.totalMem
        }.getOrDefault(0L)
        val freeStorage = runCatching {
            val dir = context.getExternalFilesDir(null)
                ?: Environment.getExternalStorageDirectory()
            StatFs(dir.absolutePath).availableBytes
        }.getOrDefault(0L)
        return DeviceCapacity(totalRamBytes = totalRam, freeStorageBytes = freeStorage)
    }

    /** True when the catalog file is already in Orion's own model folder. */
    fun localFile(context: Context, model: CatalogModel): File? {
        val dir = context.getExternalFilesDir(null) ?: return null
        val f = File(File(dir, "models"), model.fileName)
        return if (f.isFile && f.length() > model.sizeBytes * 9 / 10) f else null
    }

    fun byId(id: String): CatalogModel? = all.firstOrNull { it.id == id }

    fun byFileName(name: String): CatalogModel? =
        all.firstOrNull { it.fileName.equals(name, ignoreCase = true) }
}
