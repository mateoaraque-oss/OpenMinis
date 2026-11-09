package com.openminis.app.sandbox.offload

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/**
 * [T-orion-app-cli] `android-app` - list and launch installed apps.
 *
 * Opening an app is the one phone action the other offload commands cannot
 * express in a single call: `android-a11y-cli` can get there by pressing HOME,
 * dumping the launcher and tapping a label, which costs three round trips and
 * breaks on a launcher page that does not show the icon. A 1-3B on-device
 * model gets that sequence wrong far more often than a cloud model, so the
 * device exposes the operation directly.
 *
 * Usage:
 *   android-app list [--query <text>]
 *   android-app launch <name|package>
 *   android-app open <name|package>        (alias of launch)
 *
 * Matching is case/accent-insensitive: exact package, then exact label, then
 * label prefix, then label/package substring. Ambiguous matches return the
 * candidates instead of guessing, so the caller can retry with a package.
 */
class AppOffloadHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("h", "help")) return NativeOffloadResult(0, HELP)
        val sub = args.positional.firstOrNull()
            ?: return NativeOffloadResult(2, "android-app: missing subcommand\n$HELP")

        return try {
            when (sub) {
                "list" -> list(args, args.get("query", "filter"))
                "launch", "open" -> {
                    val target = args.positional.getOrNull(1)
                    if (target.isNullOrBlank()) {
                        NativeOffloadResult(2, "android-app: launch needs an app name or package\n$HELP")
                    } else {
                        launch(target, args)
                    }
                }
                else -> NativeOffloadResult(2, "android-app: unknown subcommand '$sub'\n$HELP")
            }
        } catch (t: Throwable) {
            val body = JSONObject().put("error", "internal")
                .put("message", t.message ?: "unknown")
            NativeOffloadResult(1, OffloadOutput.formatBody(body.toString(), args) + "\n")
        }
    }

    private fun list(args: OffloadArgs, query: String?): NativeOffloadResult {
        val needle = query?.let(::normalize)
        val apps = launchableApps().filter { app ->
            needle == null || normalize(app.label).contains(needle) ||
                normalize(app.packageName).contains(needle)
        }
        val shown = apps.take(MAX_LIST)
        val data = JSONArray()
        for (app in shown) {
            data.put(JSONObject().put("name", app.label).put("package", app.packageName))
        }
        val body = JSONObject()
            .put("count", apps.size)
            .put("apps", data)
        if (apps.size > shown.size) body.put("truncated", true)
        return NativeOffloadResult(0, OffloadOutput.formatBody(body.toString(), args) + "\n")
    }

    private fun launch(target: String, args: OffloadArgs): NativeOffloadResult {
        val apps = launchableApps()
        val wanted = normalize(target)
        val exactPackage = apps.firstOrNull { it.packageName.equals(target.trim(), ignoreCase = true) }
        val exactLabel = apps.firstOrNull { normalize(it.label) == wanted }
        val prefix = apps.filter { normalize(it.label).startsWith(wanted) }
        val contains = apps.filter {
            normalize(it.label).contains(wanted) || normalize(it.packageName).contains(wanted)
        }
        val chosen: AppEntry? = exactPackage
            ?: exactLabel
            ?: prefix.singleOrNull()
            ?: prefix.firstOrNull()
            ?: contains.singleOrNull()
            ?: contains.firstOrNull()

        if (chosen == null) {
            return notFound(target, args, contains)
        }
        val intent = context.packageManager.getLaunchIntentForPackage(chosen.packageName)
            ?: Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(chosen.packageName, chosen.activityName)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        val body = JSONObject()
            .put("ok", true)
            .put("launched", chosen.label)
            .put("package", chosen.packageName)
        return NativeOffloadResult(0, OffloadOutput.formatBody(body.toString(), args) + "\n")
    }

    private fun notFound(
        target: String,
        args: OffloadArgs,
        suggestions: List<AppEntry>,
    ): NativeOffloadResult {
        val data = JSONArray()
        for (app in suggestions.take(MAX_SUGGESTIONS)) {
            data.put(JSONObject().put("name", app.label).put("package", app.packageName))
        }
        val body = JSONObject()
            .put("error", "app_not_found")
            .put("message", "No installed app matches \"$target\".")
            .put("did_you_mean", data)
        return NativeOffloadResult(1, OffloadOutput.formatBody(body.toString(), args) + "\n")
    }

    private data class AppEntry(
        val label: String,
        val packageName: String,
        val activityName: String,
    )

    /** Launchable activities, one entry per package, alphabetical by label. */
    private fun launchableApps(): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, 0)
        val byPackage = LinkedHashMap<String, AppEntry>()
        for (info in resolved) {
            val activity = info.activityInfo ?: continue
            val packageName = activity.packageName ?: continue
            if (byPackage.containsKey(packageName)) continue
            val label = runCatching { info.loadLabel(pm).toString() }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: packageName
            byPackage[packageName] = AppEntry(
                label = label,
                packageName = packageName,
                activityName = activity.name,
            )
        }
        return byPackage.values.sortedBy { normalize(it.label) }
    }

    /** Lowercase, accents stripped: "Música" and "musica" compare equal. */
    private fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        return decomposed.replace(Regex("""\p{Mn}+"""), "")
            .lowercase(Locale.ROOT)
            .trim()
    }

    private companion object {
        const val MAX_LIST = 200
        const val MAX_SUGGESTIONS = 5
        const val HELP = """android-app - list and launch installed apps

Usage:
  android-app list [--query <text>]   launchable apps (name + package)
  android-app launch <name|package>   open an app by name or package
  android-app open <name|package>     alias of launch
"""
    }
}
