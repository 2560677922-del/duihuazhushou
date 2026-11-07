package com.dbpg.duihuazhushou;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/**
 * 应用级生命周期钩子：让 ST 兼容引擎始终挂在前台窗口上。
 *
 * <p>只做一件事 —— 任何 Activity 进入前台时调用 {@link MainActivity#attachEngineTo}。
 * 原因见该方法的注释：隐藏 WebView 留在后台窗口会被 Chromium 判成 hidden 页面，
 * JS 定时器和部分异步任务被冻结，原生设置页的桥命令会「写了数据但不回传」。
 *
 * <p>为什么用 {@code ActivityLifecycleCallbacks} 而不是让每个页面自己调：
 * 目前有十几个页面走桥（设置、资产、生图配置、聊天、迁移……），逐个改既容易漏，
 * 以后新增页面也会忘。回调里 {@code onActivityStarted} 早于 {@code onResume}，
 * 页面在 onResume 里发的 loadConfig / command 一定能跑在已挂载好的引擎上；
 * {@code onActivityResumed} 再兜一次底（方法幂等，重复调用没有副作用）。
 */
public final class DhpApp extends Application {

    /** 全局引用：给没有 Context 的单例（ConnectionStore 落盘）用，不持有 Activity。 */
    public static DhpApp instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity a) { MainActivity.attachEngineTo(a); }

            @Override public void onActivityResumed(Activity a) { MainActivity.attachEngineTo(a); }

            @Override public void onActivityCreated(Activity a, Bundle b) { }

            @Override public void onActivityPaused(Activity a) { }

            @Override public void onActivityStopped(Activity a) { }

            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }

            @Override public void onActivityDestroyed(Activity a) { }
        });
    }
}
