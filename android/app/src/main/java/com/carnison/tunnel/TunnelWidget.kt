package com.carnison.tunnel

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home screen widget: status, server and a power button. */
class TunnelWidget : AppWidgetProvider() {
    companion object {
        fun update(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, TunnelWidget::class.java))
            if (ids.isEmpty()) return
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            v.setTextViewText(R.id.status, Vpn.statusText(ctx))
            v.setTextViewText(R.id.server, Vpn.lastServerName(ctx))
            v.setInt(R.id.power, "setBackgroundResource", if (Vpn.state == Vpn.DISCONNECTED) R.drawable.power_off else R.drawable.power_on)
            // straight to the service: a foreground service may be started from a widget tap
            val toggle = Intent(ctx, TunnelVpnService::class.java).setAction(TunnelVpnService.ACTION_TOGGLE)
            v.setOnClickPendingIntent(R.id.power, PendingIntent.getForegroundService(ctx, 0, toggle, PendingIntent.FLAG_IMMUTABLE))
            val open = Intent(ctx, MainActivity::class.java)
            v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(ctx, 1, open, PendingIntent.FLAG_IMMUTABLE))
            mgr.updateAppWidget(ids, v)
        }
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = update(ctx)
}
