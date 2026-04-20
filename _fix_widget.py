#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
按澎湃OS3规范重做 4x2 小部件：
- 深色毛玻璃风格背景
- 左：当前网络模式（大字 LTE/5G）+ 运营商 + 信号信息
- 右：圆形切换按钮
- 安全区/圆角/深色模式全部按规范
"""
import os

BASE = r"d:\GitHub\NetworkSwitch\app\src\main"
RES = os.path.join(BASE, "res")
XML = os.path.join(RES, "xml")
LAYOUT = os.path.join(RES, "layout")
DRAWABLE = os.path.join(RES, "drawable")
VALUES = os.path.join(RES, "values")
JAVA = os.path.join(BASE, r"java\io\github\stalxjason\networkswitch")

# ─────────────────────────────────────────────────────────────────────────────
# 1. widget_background.xml — 深色圆角背景，适配澎湃OS
# ─────────────────────────────────────────────────────────────────────────────
widget_bg = r"""<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#E61A1A1A" />
    <corners android:radius="22dp" />
</shape>"""

with open(os.path.join(DRAWABLE, "widget_background.xml"), "w", encoding="utf-8") as f:
    f.write(widget_bg)
print("widget_background.xml written")

# ─────────────────────────────────────────────────────────────────────────────
# 2. widget_network.xml — 4x2 布局
#    整体：左（模式+运营商+信号）+ 右（切换按钮）
#    远程视图只能用简单布局，不能 ConstraintLayout
# ─────────────────────────────────────────────────────────────────────────────
widget_layout = r"""<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/widget_root"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/widget_background"
    android:padding="14dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:gravity="center_vertical"
        android:orientation="horizontal">

        <!-- 左侧信息区 -->
        <LinearLayout
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:gravity="center_vertical"
            android:orientation="vertical">

            <!-- 当前网络模式（大字） -->
            <TextView
                android:id="@+id/tv_widget_mode"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="LTE"
                android:textColor="#FFFFFF"
                android:textSize="28sp"
                android:textStyle="bold" />

            <!-- 运营商 + 网络描述 -->
            <TextView
                android:id="@+id/tv_widget_carrier"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="2dp"
                android:text="中国电信"
                android:textColor="#80FFFFFF"
                android:textSize="12sp" />

            <!-- 信号信息（格数 / dBm） -->
            <TextView
                android:id="@+id/tv_widget_signal"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="2dp"
                android:text="3/4 格  -89 dBm"
                android:textColor="#66FFFFFF"
                android:textSize="11sp" />

        </LinearLayout>

        <!-- 右侧切换按钮 -->
        <FrameLayout
            android:id="@+id/btn_widget_toggle"
            android:layout_width="48dp"
            android:layout_height="48dp"
            android:layout_gravity="center_vertical">

            <ImageView
                android:layout_width="48dp"
                android:layout_height="48dp"
                android:src="@drawable/widget_btn_bg"
                android:contentDescription="切换" />

            <TextView
                android:id="@+id/tv_widget_btn_text"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:gravity="center"
                android:text="4G/5G"
                android:textColor="#FFFFFF"
                android:textSize="10sp"
                android:textStyle="bold" />

        </FrameLayout>

    </LinearLayout>

</FrameLayout>"""

with open(os.path.join(LAYOUT, "widget_network.xml"), "w", encoding="utf-8") as f:
    f.write(widget_layout)
print("widget_network.xml written")

# ─────────────────────────────────────────────────────────────────────────────
# 3. widget_btn_bg.xml — 切换按钮圆形背景
# ─────────────────────────────────────────────────────────────────────────────
btn_bg = r"""<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="oval">
    <solid android:color="#1E88E5" />
</shape>"""

with open(os.path.join(DRAWABLE, "widget_btn_bg.xml"), "w", encoding="utf-8") as f:
    f.write(btn_bg)
print("widget_btn_bg.xml written")

# ─────────────────────────────────────────────────────────────────────────────
# 4. network_widget_info.xml — 4x2 配置
#    targetCellWidth=4, targetCellHeight=2
#    minResizeWidth/minResizeHeight 也要对应
# ─────────────────────────────────────────────────────────────────────────────
widget_info = r"""<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:initialLayout="@layout/widget_network"
    android:minWidth="250dp"
    android:minHeight="110dp"
    android:minResizeWidth="250dp"
    android:minResizeHeight="110dp"
    android:resizeMode="none"
    android:updatePeriodMillis="1800000"
    android:widgetCategory="home_screen"
    android:description="@string/widget_description"
    android:previewLayout="@layout/widget_network"
    android:targetCellWidth="4"
    android:targetCellHeight="2" />"""

with open(os.path.join(XML, "network_widget_info.xml"), "w", encoding="utf-8") as f:
    f.write(widget_info)
print("network_widget_info.xml written")

# ─────────────────────────────────────────────────────────────────────────────
# 5. NetworkWidgetProvider.kt — 重写：显示模式/运营商/信号，按钮独立点击
# ─────────────────────────────────────────────────────────────────────────────
widget_kt = r"""package io.github.stalxjason.networkswitch

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 网络切换桌面 Widget (4x2 澎湃OS3 深色风格)
 *
 * 左：当前网络模式 (LTE/5G NR) + 运营商 + 信号强度
 * 右：切换按钮
 */
class NetworkWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE = "io.github.stalxjason.networkswitch.ACTION_TOGGLE"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        fun updateWidget(context: Context) {
            val intent = Intent(context, NetworkWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                val ids = AppWidgetManager.getInstance(context)
                    .getAppWidgetIds(ComponentName(context, NetworkWidgetProvider::class.java))
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            handleToggle(context)
        }
    }

    private fun handleToggle(context: Context) {
        scope.launch {
            val result = NetworkModeHelper.toggleNetworkMode(context)
            val msg = if (result.success) {
                "已切换到 ${result.currentMode.label}"
            } else {
                result.message
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(
                ComponentName(context, NetworkWidgetProvider::class.java)
            )
            onUpdate(context, appWidgetManager, ids)
        }
    }

    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        widgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_network)

        // 当前网络模式
        val currentMode = NetworkModeHelper.getCurrentMode(context)
        views.setTextViewText(R.id.tv_widget_mode, currentMode.label)

        // 运营商 + 信号
        try {
            val signalInfo = NetworkInfoHelper.getSignalInfo(context)
            val dataSim = signalInfo.sims.find { it.isDataSim }
            val carrier = dataSim?.carrierName ?: "未知运营商"
            val netType = dataSim?.networkTypeName
            val carrierText = buildString {
                append(carrier)
                if (netType != null) append(" · $netType")
            }
            views.setTextViewText(R.id.tv_widget_carrier, carrierText)

            // 信号信息
            val dbm = dataSim?.signalDbm
            val level = dataSim?.signalLevel ?: 0
            val signalText = buildString {
                if (level > 0) append("$level/4 格")
                if (dbm != null) {
                    if (isNotEmpty()) append("  ")
                    append("${dbm} dBm")
                }
                if (isEmpty()) append("无信号")
            }
            views.setTextViewText(R.id.tv_widget_signal, signalText)
        } catch (_: Exception) {
            views.setTextViewText(R.id.tv_widget_carrier, "未知运营商")
            views.setTextViewText(R.id.tv_widget_signal, "")
        }

        // 切换按钮点击
        val toggleIntent = Intent(context, NetworkWidgetProvider::class.java).apply {
            action = ACTION_TOGGLE
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.btn_widget_toggle, pendingIntent)

        // 整个 widget 点击也能打开 app
        val openAppIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (openAppIntent != null) {
            val openPendingIntent = PendingIntent.getActivity(
                context, 1, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, openPendingIntent)
        }

        appWidgetManager.updateAppWidget(widgetId, views)
    }
}
"""

with open(os.path.join(JAVA, "NetworkWidgetProvider.kt"), "w", encoding="utf-8") as f:
    f.write(widget_kt)
print("NetworkWidgetProvider.kt written")

# ─────────────────────────────────────────────────────────────────────────────
# 6. strings.xml — 更新 widget 描述
# ─────────────────────────────────────────────────────────────────────────────
strings = r"""<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">网络切换</string>
    <string name="widget_name">网络切换</string>
    <string name="widget_description">显示当前网络模式，一键切换 4G/5G</string>
</resources>"""

with open(os.path.join(VALUES, "strings.xml"), "w", encoding="utf-8") as f:
    f.write(strings)
print("strings.xml written")

print("\nAll done.")
