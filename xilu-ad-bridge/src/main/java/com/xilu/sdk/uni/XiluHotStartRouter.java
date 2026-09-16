package com.xilu.sdk.uni;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.Map;

import com.xilu.sdk.ADXiluSdk;
import com.xilu.sdk.ad.data.ADXiluAdType;
import com.xilu.sdk.ad.data.IBasePosInfo;
import com.xilu.sdk.core.manager.ADSdkManager;
import com.xilu.sdk.core.manager.HotStartSplashManager;
import com.xilu.sdk.core.manager.HotStartSplashPreloadManager;
import com.xilu.sdk.core.model.InitData;

/** 热启动开屏路由：修正 uni-app 透明壳导致的宿主错位，确保广告挂在真正可见的业务页面上，每次前台回合仅展示一次。 */
public final class XiluHotStartRouter implements Application.ActivityLifecycleCallbacks {

    private static final String TAG = "XiluHotStartFix";

    /** DCloud 离线包的瞬态透明壳（起完主 Activity 即 finish，挂上去必然跟着销毁） */
    private static final String SHELL_ACTIVITY = "io.dcloud.PandoraEntry";

    /** 最小后台时长默认值：低于此视为 App 内切换；真正热启动为秒级。宿主可用 hotStartMinBackgroundMs 覆盖。 */
    private static final long DEFAULT_MIN_BACKGROUND_MS = 1000L;

    /** 两次预加载竞价的最小间隔（一次竞价约 3s 出结果，避免重复竞价） */
    private static final long BID_COOLDOWN_MS = 6000L;

    /** 预加载重试间隔（配置未到 / 竞价未出结果时） */
    private static final long ARM_RETRY_MS = 6000L;

    /** 单个前台回合内最多重试几次预加载 */
    private static final int MAX_ARM_ATTEMPTS = 8;

    /** 后台判定防抖：started 计数归零后等这么久仍无 Activity 起来，才认定 App 真的进后台 */
    private static final long BACKGROUND_CONFIRM_DELAY_MS = 250L;

    /** 非热启动宿主 Activity 前缀：广告 SDK 页面、自有开屏页。出现这些说明当前非业务页面，不挂热启动。 */
    private static final String[] NON_HOST_PREFIXES = {
            "com.xilu.sdk.uni.",           // 本插件自己的 XiluSplashActivity
            "com.qq.e.",                   // 优量汇
            "com.bytedance.",              // 穿山甲 / GroMore
            "com.kwad.",                   // 快手
            "com.baidu.",                  // 百青藤
            "com.beizi.",                  // 倍孜
            "com.octopus.",                // 章鱼
            "com.sigmob.",                 // Sigmob
            "com.wangmai.",                // 旺脉
            "com.huawei.",                 // 华为
            "com.hihonor.",                // 荣耀
            "com.google.android.gms.ads."  // AdMob
    };

    private static XiluHotStartRouter sInstance;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    // ---------------- 运行状态 ----------------
    /** 热启动开屏广告位：取 SDK 配置里的开屏广告位，缓存在这里（解析到之后不再查） */
    private String mSplashPosId;
    /** 最小后台时长（ms） */
    private long mMinBackgroundMs = DEFAULT_MIN_BACKGROUND_MS;

    // ---------------- 前台回合状态 ----------------
    /** 处于 started 状态的 Activity 数量（含透明壳），0→1 表示 App 回到前台 */
    private int mStartedCount;
    /** 是否处于一个前台回合内（后台确认有 250ms 防抖，避免壳/主 Activity 交接的瞬时归零被当成进后台） */
    private boolean mInForeground;
    /** App 进入后台的时间戳（0 表示当前在前台） */
    private long mBackgroundAt;
    /** 本次回到前台时的后台时长（ms） */
    private long mHotStartGapMs;
    /** 本次前台回合是否已经决策过（每个回合只在第一个宿主 resume 上决策一次） */
    private boolean mEpisodeDecided;
    /** SDK 热启动开关当前是否被我们压住 */
    private boolean mSuppressed;
    /** 最近一个宿主 Activity，用于预加载竞价 */
    private WeakReference<Activity> mHostRef;
    /** 已展示的热启动次数（便于真机核对"一次热启动只展示一次"） */
    private int mHandledCount;

    // ---------------- 预加载状态 ----------------
    private long mLastBidAt;
    private int mArmAttempts;
    private final Runnable mArmTask = new Runnable() {
        @Override
        public void run() {
            ensureCacheArmed("retry");
        }
    };

    /** 后台确认：计数归零后 250ms 内没有新 Activity 起来，才记为"App 进后台" */
    private final Runnable mBackgroundConfirmTask = new Runnable() {
        @Override
        public void run() {
            if (mStartedCount > 0) return;
            mInForeground = false;
            mBackgroundAt = System.currentTimeMillis();
            Log.i(TAG, "app in background");
        }
    };

    private XiluHotStartRouter() {
    }

    /** 注册路由（必须在 ADXiluSdk.init 之前调用）。热启动广告位直接取 SDK 配置中的开屏位，宿主无需额外配置。 */
    public static void install(Application app) {
        if (app == null) return;
        if (sInstance == null) {
            XiluHotStartRouter router = new XiluHotStartRouter();
            app.registerActivityLifecycleCallbacks(router);
            sInstance = router;
            Log.i(TAG, "installed (before ADXiluSdk.init)");
        }
    }

    /** 热启动广告位 = SDK 配置中的开屏位，与 SDK 内部取法一致，宿主无需额外配置。 */
    private String splashPosId() {
        if (mSplashPosId != null) return mSplashPosId;
        try {
            InitData data = ADSdkManager.getInstance().getInitData();
            if (data == null || data.getPosIdMap() == null) return null;
            for (Map.Entry<String, IBasePosInfo> entry : data.getPosIdMap().entrySet()) {
                IBasePosInfo info = entry.getValue();
                if (info != null && ADXiluAdType.TYPE_SPLASH.equals(info.getAdType())) {
                    mSplashPosId = entry.getKey();
                    break;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "resolve splash posId failed", t);
        }
        return mSplashPosId;
    }

    // ------------------------------------------------------------------
    // Application.ActivityLifecycleCallbacks
    // ------------------------------------------------------------------

    @Override
    public void onActivityStarted(Activity activity) {
        mStartedCount++;
        // 有 Activity 起来 → 取消待确认的"进后台"（壳与主 Activity 交接时会瞬时归零）
        mHandler.removeCallbacks(mBackgroundConfirmTask);
        if (mInForeground) return;

        // App 回到前台（冷启动时 mBackgroundAt=0 → 后台时长 0）
        mInForeground = true;
        mHotStartGapMs = mBackgroundAt > 0 ? System.currentTimeMillis() - mBackgroundAt : 0;
        if (mHotStartGapMs < 0) mHotStartGapMs = 0;
        mBackgroundAt = 0;
        mEpisodeDecided = false;
        mArmAttempts = 0;
        Log.i(TAG, "return to foreground, backgroundMs=" + mHotStartGapMs);
    }

    @Override
    public void onActivityStopped(Activity activity) {
        mStartedCount--;
        if (mStartedCount > 0) return;
        mStartedCount = 0;
        mHandler.removeCallbacks(mBackgroundConfirmTask);
        mHandler.postDelayed(mBackgroundConfirmTask, BACKGROUND_CONFIRM_DELAY_MS);
    }

    @Override
    public void onActivityResumed(Activity activity) {
        if (activity == null || !ADXiluSdk.getInstance().isInit()) return;
        final String who = activity.getClass().getName();

        // 透明壳（DCloud 的瞬态入口）：不是可见页面，只保证 SDK 自己那次触发被压住
        if (isTransientHost(activity)) {
            suppress();
            return;
        }

        mHostRef = new WeakReference<>(activity);

        // 广告 SDK 自己的页面 / 我们自己的开屏页：当前显示的不是业务页面，本回合不再考虑热启动
        if (isNonHost(activity)) {
            suppress();
            if (!mEpisodeDecided) {
                mEpisodeDecided = true;
                Log.i(TAG, "resume " + who + " -> non-host (ad sdk / own splash), episode decided");
            }
            return;
        }

        // 业务页面：每个前台回合只决策一次
        if (mEpisodeDecided) {
            suppress();
            Log.i(TAG, "resume " + who + " -> episode already decided, skip (in-app navigation)");
            return;
        }
        mEpisodeDecided = true;

        if (mHotStartGapMs >= mMinBackgroundMs) {
            Log.i(TAG, "resume " + who + " -> host, backgroundMs=" + mHotStartGapMs + ", display hot-start splash");
            displayHotStart(activity, mHotStartGapMs);
        } else {
            Log.i(TAG, "resume " + who + " -> host, backgroundMs=" + mHotStartGapMs
                    + " < min=" + mMinBackgroundMs + ", not a hot start");
        }

        // 无论是否展示，都保证下一次热启动有缓存可用
        ensureCacheArmed("host-resume");
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }

    // ------------------------------------------------------------------
    // 热启动展示
    // ------------------------------------------------------------------

    /** 在当前页面展示热启动开屏：临时恢复 SDK 开关，调用后立即压回，SDK 自动触发始终关闭。 */
    private void displayHotStart(Activity activity, long backgroundMs) {
        final HotStartSplashManager mgr = HotStartSplashManager.getInstance();
        if (mgr.isShowing()) {
            Log.i(TAG, "skip display: hot-start splash is showing");
            return;
        }
        if (mgr.isDailyLimitReached()) {
            Log.i(TAG, "skip display: daily limit reached");
            return;
        }
        restore();
        try {
            // 缓存命中则由 SDK 展示（容器挂在本 activity），未命中只补竞价、不展示
            mgr.onHotStart(activity, backgroundMs);
            mHandledCount++;
            Log.i(TAG, "display hot-start on " + activity.getClass().getName()
                    + " backgroundMs=" + backgroundMs + " count=" + mHandledCount);
        } catch (Throwable t) {
            Log.e(TAG, "onHotStart failed", t);
        } finally {
            suppress();
        }
    }

    // ------------------------------------------------------------------
    // 预加载竞价（热启动广告的缓存来源）
    // ------------------------------------------------------------------

    /** 保证热启动缓存就绪：无有效缓存时发起预加载竞价（onHotStart 仅缓存命中时展示）。 */
    private void ensureCacheArmed(String reason) {
        final String posId = splashPosId();
        if (TextUtils.isEmpty(posId)) return;

        if (HotStartSplashPreloadManager.getInstance().hasValidCache(posId)) {
            mArmAttempts = 0;
            return;
        }

        // 配置未到 或 服务端把热启动关了：稍后重试，不做无谓竞价
        InitData.SplashLaunch cfg = serverConfig();
        if (cfg == null || cfg.getHotStart() != 1) {
            Log.i(TAG, "arm skipped (" + reason + "): hot-start config not ready/enabled");
            scheduleArm();
            return;
        }

        long now = System.currentTimeMillis();
        if (mLastBidAt > 0 && now - mLastBidAt < BID_COOLDOWN_MS) {
            scheduleArm();
            return;
        }

        final Activity activity = mHostRef != null ? mHostRef.get() : null;
        mLastBidAt = now;
        restore();
        try {
            HotStartSplashPreloadManager.getInstance().startPreloadBid(posId, activity, true);
            Log.i(TAG, "arm preload bid (" + reason + "): posId=" + posId
                    + ", activity=" + (activity != null ? activity.getClass().getSimpleName() : "null"));
        } catch (Throwable t) {
            Log.w(TAG, "arm preload failed", t);
        } finally {
            suppress();
        }
        scheduleArm();
    }

    /** 单回合内有限次重试：竞价失败或配置还没到时不至于一直空跑 */
    private void scheduleArm() {
        if (mArmAttempts >= MAX_ARM_ATTEMPTS) return;
        mArmAttempts++;
        mHandler.removeCallbacks(mArmTask);
        mHandler.postDelayed(mArmTask, ARM_RETRY_MS);
    }

    // ------------------------------------------------------------------
    // SDK 热启动开关 压制/恢复
    // ------------------------------------------------------------------

    /** 压制 SDK 自带热启动：仅改开关，其余字段保留服务端下发值。 */
    private void suppress() {
        if (mSuppressed) return;
        InitData.SplashLaunch server = serverConfig();
        if (server == null) return;    // 配置还没到，SDK 开关本来就是关的
        if (server.getHotStart() == 0) {
            mSuppressed = true;        // 服务端本来就关着，等价于已压制
            return;
        }
        HotStartSplashManager.getInstance().applySplashLaunch(copyOf(server, 0));
        mSuppressed = true;
        Log.i(TAG, "suppress SDK hot-start (hotStart=0)");
    }

    /** 恢复服务端下发的热启动配置（仅在调用 SDK 瞬间）。 */
    private void restore() {
        if (!mSuppressed) return;
        InitData.SplashLaunch server = serverConfig();
        if (server == null) {
            mSuppressed = false;
            return;
        }
        if (server.getHotStart() == 0) {
            mSuppressed = false;
            return;
        }
        HotStartSplashManager.getInstance().applySplashLaunch(copyOf(server, server.getHotStart()));
        mSuppressed = false;
        Log.i(TAG, "restore SDK hot-start (hotStart=" + server.getHotStart() + ")");
    }

    private static InitData.SplashLaunch serverConfig() {
        try {
            InitData data = ADSdkManager.getInstance().getInitData();
            return data == null ? null : data.getSplashLaunch();
        } catch (Throwable t) {
            return null;
        }
    }

    private static InitData.SplashLaunch copyOf(InitData.SplashLaunch src, int hotStart) {
        InitData.SplashLaunch dst = new InitData.SplashLaunch();
        dst.setHotStart(hotStart);
        if (src != null) {
            dst.setHotTime(src.getHotTime());
            dst.setHotDiffSlot(src.getHotDiffSlot());
            dst.setHotDailyLimit(src.getHotDailyLimit());
        }
        return dst;
    }

    // ------------------------------------------------------------------
    // 宿主判定
    // ------------------------------------------------------------------

    /** 瞬态宿主判定：DCloud 透明壳 / 正在 finish / 主题 windowIsTranslucent（挂上去必然随即销毁）。 */
    private static boolean isTransientHost(Activity activity) {
        if (SHELL_ACTIVITY.equals(activity.getClass().getName())) return true;
        if (activity.isFinishing()) return true;
        try {
            // LayoutParams.windowIsTranslucent 是 @hide 字段，编译期不可见；改为解析主题属性
            android.util.TypedValue tv = new android.util.TypedValue();
            boolean resolved = activity.getTheme().resolveAttribute(
                    android.R.attr.windowIsTranslucent, tv, true);
            return resolved && tv.data != 0;
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static boolean isNonHost(Activity activity) {
        String name = activity.getClass().getName();
        for (String prefix : NON_HOST_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }
}
