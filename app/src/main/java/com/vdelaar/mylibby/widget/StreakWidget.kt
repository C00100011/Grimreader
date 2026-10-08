package com.vdelaar.mylibby.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.vdelaar.mylibby.MainActivity
import com.vdelaar.mylibby.MyLibbyApp
import com.vdelaar.mylibby.core.datastore.GoalType
import com.vdelaar.mylibby.data.StreakCalculator
import com.vdelaar.mylibby.data.StreakInfo

class StreakWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val c = (context.applicationContext as MyLibbyApp).container
        val info = StreakCalculator.compute(c.db.sessions().dayTotals(), c.settings.goal.value)
        provideContent { Content(info) }
    }

    // TODO: needs tweakin'
    @Composable
    private fun Content(info: StreakInfo) {
        val ctx = androidx.glance.LocalContext.current
        val bg = ColorProvider(Color(0xFF2A211B))
        val accent = ColorProvider(Color(0xFFFFB45C))
        val fg = ColorProvider(Color(0xFFF5EDE3))
        val unit = ctx.getString(if (info.goalType == GoalType.MINUTES) com.vdelaar.mylibby.R.string.unit_min else com.vdelaar.mylibby.R.string.unit_pages)
        Column(
            modifier = GlanceModifier.fillMaxSize().background(bg).cornerRadius(24.dp).padding(14.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔥", style = TextStyle(fontSize = 22.sp))
                Spacer(GlanceModifier.width(6.dp))
                Text("${info.current}", style = TextStyle(color = accent, fontSize = 26.sp, fontWeight = FontWeight.Bold))
                Spacer(GlanceModifier.width(6.dp))
                Text(ctx.resources.getQuantityString(com.vdelaar.mylibby.R.plurals.day_word, info.current), style = TextStyle(color = fg, fontSize = 14.sp))
            }
            Spacer(GlanceModifier.height(8.dp))
            LinearProgressIndicator(
                progress = info.todayFraction,
                modifier = GlanceModifier.fillMaxWidth().height(6.dp),
                color = accent,
                backgroundColor = ColorProvider(Color(0x33FFFFFF)),
            )
            Spacer(GlanceModifier.height(6.dp))
            Text(
                if (info.todayMet) ctx.getString(com.vdelaar.mylibby.R.string.widget_goal_done) else ctx.getString(com.vdelaar.mylibby.R.string.widget_today, info.todayValue.toInt(), info.target, unit),
                style = TextStyle(color = fg, fontSize = 12.sp),
            )
        }
    }

    companion object {
        suspend fun refresh(context: Context) {
            runCatching {
                if (GlanceAppWidgetManager(context).getGlanceIds(StreakWidget::class.java).isNotEmpty()) {
                    StreakWidget().updateAll(context)
                }
            }
        }
    }
}

class StreakWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StreakWidget()
}
