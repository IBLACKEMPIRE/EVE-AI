package ai.eve.android.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import ai.eve.core.tasks.TaskTracker
import ai.eve.core.tools.RiskLevel
import ai.eve.core.tools.Tool
import ai.eve.core.tools.ToolResult
import ai.eve.core.tools.tool

/**
 * Phone actions for the prototype. Anything that reaches other people (calls, SMS) only
 * opens the system UI pre-filled — the user presses Call/Send themselves. Silent sending
 * Accessibility automation and background scheduling are later milestones (see docs/ROADMAP.md).
 */
object AndroidTools {
    fun all(ctx: Context, tasks: TaskTracker): List<Tool> = listOf(
        openApp(ctx), setAlarm(ctx), dial(ctx), composeSms(ctx), listTasks(tasks),
    )

    private fun start(ctx: Context, intent: Intent): ToolResult = try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ToolResult.ok("Opened")
    } catch (e: Exception) { ToolResult.error("No app can handle this: ${e.message}") }

    private fun openApp(ctx: Context) = tool(
        "open_app", "Open an installed app by its name.", RiskLevel.LOW, mapOf("name" to "App name as shown on the home screen"),
        describe = { "Open ${it["name"]}" },
    ) { args ->
        val wanted = args["name"].orEmpty().trim().lowercase()
        val pm = ctx.packageManager
        val launchables = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val match = launchables.map { it to it.loadLabel(pm).toString() }
            .sortedBy { (_, label) -> if (label.lowercase() == wanted) 0 else 1 }
            .firstOrNull { (_, label) -> label.lowercase() == wanted || label.lowercase().contains(wanted) }
        if (wanted.isEmpty() || match == null) ToolResult.error("No installed app named '${args["name"]}'")
        else {
            val intent = pm.getLaunchIntentForPackage(match.first.activityInfo.packageName)
            if (intent == null) ToolResult.error("Can't launch ${match.second}") else start(ctx, intent).let {
                if (it.ok) ToolResult.ok("Opened ${match.second}") else it
            }
        }
    }

    private fun setAlarm(ctx: Context) = tool(
        "set_alarm", "Create an alarm in the clock app.", RiskLevel.LOW,
        mapOf("hour" to "0-23", "minute" to "0-59", "label" to "Alarm label"),
        describe = { "Alarm at ${it["hour"]}:${it["minute"]?.padStart(2, '0')}" },
    ) { a ->
        val h = a["hour"]?.toIntOrNull(); val m = a["minute"]?.toIntOrNull() ?: 0
        if (h == null || h !in 0..23 || m !in 0..59) ToolResult.error("Invalid time")
        else start(ctx, Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, a["label"] ?: "EVE")).let {
            if (it.ok) ToolResult.ok("Alarm request sent for %02d:%02d".format(h, m)) else it
        }
    }

    private fun dial(ctx: Context) = tool(
        "dial_number", "Open the phone dialer with a number filled in. The user presses Call.", RiskLevel.LOW,
        mapOf("number" to "Phone number"), describe = { "Open dialer for ${it["number"]}" },
    ) { a ->
        val n = a["number"].orEmpty().filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (n.isEmpty()) ToolResult.error("No number") else start(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(n))))
            .let { if (it.ok) ToolResult.ok("Dialer opened with $n; the user must press Call") else it }
    }

    private fun composeSms(ctx: Context) = tool(
        "compose_sms", "Open the SMS app with a recipient and message filled in. The user presses Send.", RiskLevel.LOW,
        mapOf("number" to "Recipient phone number", "message" to "Message text"),
        describe = { "Draft SMS to ${it["number"]}" },
    ) { a ->
        val n = a["number"].orEmpty().filter { it.isDigit() || it == '+' }
        if (n.isEmpty()) ToolResult.error("No number")
        else start(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$n")).putExtra("sms_body", a["message"].orEmpty()))
            .let { if (it.ok) ToolResult.ok("SMS draft opened; the user must press Send") else it }
    }

    private fun listTasks(tasks: TaskTracker) = tool("list_tasks", "List recent EVE tasks and their status.", RiskLevel.READ_ONLY) {
        val recent = tasks.all().take(10)
        ToolResult.ok(if (recent.isEmpty()) "No tasks yet." else recent.joinToString("\n") { "${it.status}: ${it.title}" })
    }
}
