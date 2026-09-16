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

/**
 * 【uni-app 集成适配】热启动开屏的展示时机与宿主修正（不改 Xilu SDK，只用它的 public API）。
 *
 * <p><b>问题</b>：uni-app 离线包的 LAUNCHER 入口是 DCloud 的**瞬态透明壳** {@code io.dcloud.PandoraEntry}，
 * 它 resume 后立刻 startActivity(主 Activity) 并 finish 自己。而 SDK 的热启动展示点是
 * "App 回到前台后**第一个** resume 的 Activity"（见 {@code CustomActivityLifecycleCallbacks}），
 * 于是容器被挂到那个几百毫秒后就销毁的壳上 → 热启动广告"一闪就没了"。
 *
 * <p><b>需求</b>：热启动广告只属于"热启动那一刻正在显示的那个页面"，展示一次就结束；
 * App 内之后的任何跳转（含我们自己的开屏页、广告落地页）都与这次热启动广告无关，不能再冒出来。
 *
 * <p><b>做法</b>：把 SDK 的"自动热启动"整个关掉，改由本类在正确的时机、用正确的宿主主动调一次：
 * <ol>
 *   <li><b>常态压制</b>：{@code applySplashLaunch(hotStart=0)} 让 {@code isEnabled()==false}。
 *       本类在 SDK 之前注册生命周期回调，因此每次 resume 都是我们先跑 → SDK 自己那次触发永远被挡住，
 *       不会再出现挂在透明壳/开屏页/广告页上的意外展示。</li>
 *   <li><b>前台回合</b>：自己数 Activity 的 started 计数（0→1 即"App 回到前台"，
 *       App 内跳转不会掉到 0），在 0→1 时记下后台时长。</li>
 *   <li><b>只展示一次</b>：每个前台回合只在**第一个真正可见的宿主 Activity**(非透明壳、非广告页) 的 resume 上
 *       决策一次：后台时长 ≥ 阈值 → 临时恢复开关并调 {@code HotStartSplashManager.onHotStart(activity, gap)}
 *       （容器挂在当前这个页面上）；之后的任何 resume 一律跳过。</li>
 *   <li><b>喂缓存</b>：{@code onHotStart} 只在缓存命中时展示，缓存只由预加载竞价写入，而
 *       {@code startPreloadBid()} 也要求 {@code isEnabled()}。所以展示后（以及冷启动进入主页面后）
 *       由本类临时恢复开关、自己发起一次预加载竞价，再压回去——保证下一次热启动有货可展示。</li>
 * </ol>
 *
 * <p>展示/曝光/点击/上报仍全部走 SDK 原路径；展示间隔(hotTime)、每日上限(hotDailyLimit)、
 * 是否区分冷热广告位(hotDiffSlot) 仍由 SDK 内部按服务端配置判定。
 */
public final class XiluHotStartRouter implements Application.ActivityLifecycleCallbacks {

    private static final String TAG = "XiluHotStartFix";

    /** DCloud 离线包的瞬态透明壳（起完主 Activity 即 finish，挂上去必然跟着销毁） */
    private static final String SHELL_ACTIVITY = "io.dcloud.PandoraEntry";

    /**
     * 最小后台时长默认值：低于它的 resume 视为"App 内活动切换"而不是热启动。
     * 真正的热启动（回桌面/切到别的 App 再回来）是秒级起步；壳→主 Activity 的切换只有百毫秒级。
     * 宿主可用 init 的 hotStartMinBackgroundMs 覆盖。
     */
    private static final long DEFAULT_MIN_BACKGROUND_MS = 1000L;

    /** 两次预加载竞价的最小间隔（一次竞价约 3s 出结果，避免重复竞价） */
    private static final long BID_COOLDOWN_MS = 6000L;

    /** 预加载重试间隔（配置未到 / 竞价未出结果时） */
    private static final long ARM_RETRY_MS = 6000L;

    /** 单个前台回合内最多重试几次预加载 */
    private static final int MAX_ARM_ATTEMPTS = 8;

    /** 后台判定防抖：started 计数归零后等这么久仍无 Activity 起来，才认定 App 真的进后台 */
    private static final long BACKGROUND_CONFIRM_DELAY_MS = 250L;

    /**
     * 不作为"热启动宿主"的 Activity 前缀：广告 SDK 自己的页面、我们自己的开屏页。
     * 出现在这些页面上时，说明当前显示的不是业务页面，不挂热启动开屏。
     */
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

    /**
     * 注册路由（必须在 {@code ADXiluSdk.getInstance().init(...)} 之前调用，保证回调先于 SDK 执行）。
     * 热启动用的广告位不需要宿主传：直接用 SDK 配置里的开屏广告位，见 {@link #splashPosId()}。
     *
     * @param app Application
     */
    public static void install(Application app) {
        if (app == null) return;
        if (sInstance == null) {
            XiluHotStartRouter router = new XiluHotStartRouter();
            app.registerActivityLifecycleCallbacks(router);
            sInstance = router;
            Log.i(TAG, "installed (before ADXiluSdk.init)");
        }
    }

    /**
     * 热启动开屏广告位 = SDK 配置里的开屏广告位（posIdMap 中 adType 为 splash 的那一个），
     * 与 SDK 自己 {@code fetchSplashPosId()} 的取法一致，因此宿主不需要额外配置。
     */
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

    /**
     * 在当前这个页面上展示热启动开屏广告。
     * 只临时恢复 SDK 开关，调用后立刻压回去——SDK 自己的自动触发始终处于关闭状态。
     */
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

    /**
     * 保证热启动缓存已就绪：没有有效缓存时发起一次预加载竞价。
     * 这次竞价是热启动广告能展示的前提（{@code onHotStart} 只在缓存命中时展示）。
     */
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

    /** 压下 SDK 自带热启动：只改开关，其余字段保留服务端下发值 */
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

    /** 恢复服务端下发的热启动配置（只在我们自己调用 SDK 的瞬间） */
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

    /** 瞬态宿主判定：DCloud 透明壳 / 正在 finish / 主题声明了 windowIsTranslucent（挂上去必然跟着销毁） */
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
