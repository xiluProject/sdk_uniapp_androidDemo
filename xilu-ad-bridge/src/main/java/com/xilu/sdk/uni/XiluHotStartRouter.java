package com.xilu.sdk.uni;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import com.xilu.sdk.ADXiluSdk;
import com.xilu.sdk.ad.data.ADXiluAdType;
import com.xilu.sdk.ad.data.ADXiluSplashAdInfo;
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

    /** SDK 的 5 秒兜底自动关闭时长 */
    private static final long SDK_AUTO_CLOSE_MS = 5000L;

    /**
     * 看护余量：SDK 兜底时间 + 此余量后浮层仍未关闭，就强制清理。
     * SDK 的 cleanup() 若在 release() 抛异常，容器会留在窗口上且 mIsShowing 恒为 true（以后不再弹）。
     */
    private static final long WATCHDOG_MARGIN_MS = 3000L;

    /** 唯一的热启动宿主页面；派生类自动认，其他入口用 {@link #addHostActivity(String)} 登记 */
    private static final String HOST_ACTIVITY = "io.dcloud.PandoraEntryActivity";

    /** 额外登记的宿主页面类名（宿主自定义入口 Activity 时用） */
    private static final java.util.Set<String> sExtraHosts = new java.util.HashSet<>();

    /** 登记额外的热启动宿主页面类名（入口 Activity 不继承 PandoraEntryActivity 时用） */
    public static void addHostActivity(String className) {
        if (className != null && className.length() > 0) {
            synchronized (sExtraHosts) {
                sExtraHosts.add(className);
            }
        }
    }

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
    /** 当前前台停留的是不是宿主业务页 */
    private boolean mLastForegroundWasHost = true;
    /** 本次后台停留是否由"非宿主页（广告落地页等）"造成 —— 是的话回到前台不算热启动 */
    private boolean mBackgroundFromAd;
    /** 本回合开始时的 {@link #mBackgroundFromAd} 快照（一个回合只看一次） */
    private boolean mEpisodeFromAd;
    /** SDK 热启动开关当前是否被我们压住 */
    private boolean mSuppressed;
    /** 最近一个宿主 Activity，用于预加载竞价 */
    private WeakReference<Activity> mHostRef;
    /** 已展示的热启动次数 */
    private int mHandledCount;
    /** 本次热启动浮层开始展示的时间戳（0 表示当前没有在展示） */
    private long mHotStartShownAt;
    /**
     * 是否已经见过一次"宿主业务页 resume"。
     * 冷启动时「透明壳 → 业务页」的交接会被计数逻辑当成一次后台→前台，在这之前不允许热启动，
     * 否则冷启动会多挂一次开屏，并把 SDK 的展示时间写掉、挡掉后续真正的热启动。
     */
    private boolean mHostResumedOnce;

    /** 热启动看护任务：SDK 兜底链失效时强制清理（原因见 WATCHDOG_MARGIN_MS） */
    private final Runnable mHotStartWatchdog = new Runnable() {
        @Override
        public void run() {
            final HotStartSplashManager mgr = HotStartSplashManager.getInstance();
            boolean showing;
            try {
                showing = mgr.isShowing();
            } catch (Throwable t) {
                showing = false;
            }
            if (!showing) {
                mHotStartShownAt = 0;
                Log.i(TAG, "watchdog: hot-start overlay already closed, nothing to do");
                return;
            }
            long shownMs = mHotStartShownAt > 0 ? System.currentTimeMillis() - mHotStartShownAt : -1;
            Log.e(TAG, "watchdog: SDK 热启动兜底链失效（浮层已挂 " + shownMs
                    + "ms 仍未关闭）→ 强制清理，避免白屏卡死 + 以后不再展示");
            forceCleanup("watchdog");
        }
    };

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
            // 记住这次后台的"来源"：进后台前停在广告落地页等非宿主页，就不是用户自己退出 App，
            // 回来时不该弹热启动开屏（用户并没有重新打开 App）
            mBackgroundFromAd = !mLastForegroundWasHost;
            Log.i(TAG, "app in background" + (mBackgroundFromAd ? " (from ad/non-host page)" : " (from host page)"));
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
        // 本回合的"后台来源"快照，随后清掉，保证只影响这一个回合
        mEpisodeFromAd = mBackgroundFromAd;
        mBackgroundFromAd = false;
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

        // 非宿主页面（广告 SDK 落地页、下载器、我们自己的开屏页…）：当前显示的不是业务页面，
        // 本回合不再考虑热启动
        if (!isHost(activity)) {
            mLastForegroundWasHost = false;
            suppress();
            if (!mEpisodeDecided) {
                mEpisodeDecided = true;
                Log.i(TAG, "resume " + who + " -> non-host (ad sdk page / not the uni-app host), episode decided");
            }
            return;
        }
        mLastForegroundWasHost = true;

        // 业务页面：每个前台回合只决策一次
        if (mEpisodeDecided) {
            suppress();
            Log.i(TAG, "resume " + who + " -> episode already decided, skip (in-app navigation)");
            return;
        }
        mEpisodeDecided = true;

        // 冷启动交接期不算热启动（壳 stop → 业务页 resume）
        if (!mHostResumedOnce) {
            mHostResumedOnce = true;
            Log.i(TAG, "resume " + who + " -> first host resume (cold-start handoff), 不作为热启动");
            ensureCacheArmed("first-host");
            return;
        }

        // 只有"真正的 App 后台→前台"才算热启动。
        // 如果这次后台是进广告落地页造成的（点广告 → 拉起外部浏览器/应用 → App 被切后台），
        // 用户按返回回来并不等于"重新打开 App"，不算热启动。
        if (mEpisodeFromAd) {
            Log.i(TAG, "resume " + who + " -> 本次后台由广告/非宿主页离开造成，不算热启动");
            ensureCacheArmed("host-resume");
            return;
        }

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
        // 闸门：本进程没请求过开屏，就不弹热启动开屏（与预加载闸门一致）
        if (!sSplashRequestedInProcess) {
            Log.i(TAG, "skip display: 本进程未请求过开屏，不展示热启动");
            return;
        }
        if (mgr.isShowing()) {
            long shownMs = mHotStartShownAt > 0 ? System.currentTimeMillis() - mHotStartShownAt : -1;
            if (mHotStartShownAt > 0 && shownMs > SDK_AUTO_CLOSE_MS + WATCHDOG_MARGIN_MS) {
                // 上一次的浮层残留超时 → 先强制清理，否则这次也会被 isShowing 挡住（且白屏一直挡着）
                Log.e(TAG, "previous hot-start overlay stuck for " + shownMs + "ms, force cleanup first");
                forceCleanup("stale-before-display");
            } else {
                Log.i(TAG, "skip display: hot-start splash is showing");
                return;
            }
        }
        if (mgr.isDailyLimitReached()) {
            Log.i(TAG, "skip display: daily limit reached");
            return;
        }

        // 没有可展示的广告数据就什么都不做：不调 SDK、不挂容器
        final String posId = splashPosId();
        HotStartSplashPreloadManager.PreloadCache cache = null;
        try {
            if (!TextUtils.isEmpty(posId)) {
                cache = HotStartSplashPreloadManager.getInstance().getValidCache(posId);
            }
        } catch (Throwable t) {
            Log.w(TAG, "read hot-start cache failed", t);
        }
        if (cache == null || !(cache.adInfo instanceof ADXiluSplashAdInfo)) {
            Log.i(TAG, "skip display: 无有效热启动广告数据（cache="
                    + (cache == null ? "null" : "adInfo=" + cache.adInfo) + "）→ 不展示、不挂容器");
            mHotStartShownAt = 0;
            return;
        }
        Log.i(TAG, "hot-start 有缓存（platform=" + cache.platform + "）→ 交给 SDK 展示");

        restore();
        boolean showing = false;
        try {
            // 缓存命中则由 SDK 展示（容器挂在本 activity），未命中只补竞价、不展示
            mgr.onHotStart(activity, backgroundMs);
            mHandledCount++;
            showing = mgr.isShowing();
            Log.i(TAG, "display hot-start: count=" + mHandledCount + " showing=" + showing);
        } catch (Throwable t) {
            Log.e(TAG, "onHotStart failed", t);
            // SDK 可能已经挂上容器才抛异常（异常时它自己的 catch 也未必来得及清）→ 这里兜一层
            forceCleanup("onHotStart-threw");
            showing = false;
        } finally {
            suppress();
        }
        // 看护：SDK 的 5 秒兜底 + 余量后仍没关闭，就强制清理
        mHandler.removeCallbacks(mHotStartWatchdog);
        if (showing) {
            mHotStartShownAt = System.currentTimeMillis();
            mHandler.postDelayed(mHotStartWatchdog, SDK_AUTO_CLOSE_MS + WATCHDOG_MARGIN_MS);
        } else {
            mHotStartShownAt = 0;
        }
    }

    /**
     * 强制清理 SDK 的热启动浮层：先反射调 SDK 的 cleanup()，若它没摘容器/没复位状态，
     * 再直接摘 mSplashContainer 并复位 mIsShowing（否则白屏卡死且以后不再弹）。
     */
    private void forceCleanup(String reason) {
        mHandler.removeCallbacks(mHotStartWatchdog);
        final HotStartSplashManager mgr = HotStartSplashManager.getInstance();

        // ① SDK 自己的 cleanup()
        try {
            Method m = HotStartSplashManager.class.getDeclaredMethod("cleanup");
            m.setAccessible(true);
            m.invoke(mgr);
            Log.w(TAG, "forceCleanup(" + reason + "): SDK cleanup() invoked");
        } catch (Throwable t) {
            Log.e(TAG, "forceCleanup(" + reason + "): SDK cleanup() failed", t);
        }

        // ② 兜底：直接摘容器 + 复位 mIsShowing
        try {
            boolean stillShowing = mgr.isShowing();
            Field fContainer = HotStartSplashManager.class.getDeclaredField("mSplashContainer");
            fContainer.setAccessible(true);
            Object container = fContainer.get(mgr);
            if (container instanceof View) {
                View v = (View) container;
                if (v.getParent() instanceof ViewGroup) {
                    ((ViewGroup) v.getParent()).removeView(v);
                    Log.w(TAG, "forceCleanup(" + reason + "): removed leftover splash container");
                }
                fContainer.set(mgr, null);
            }
            Field fShowing = HotStartSplashManager.class.getDeclaredField("mIsShowing");
            fShowing.setAccessible(true);
            if (fShowing.getBoolean(mgr)) {
                fShowing.setBoolean(mgr, false);
                Log.w(TAG, "forceCleanup(" + reason + "): mIsShowing reset to false (was " + stillShowing + ")");
            }
            mHotStartShownAt = 0;
        } catch (Throwable t) {
            Log.e(TAG, "forceCleanup(" + reason + "): direct cleanup failed", t);
        }
    }

    // ------------------------------------------------------------------
    // 预加载竞价（热启动广告的缓存来源）
    // ------------------------------------------------------------------

    /**
     * 本进程是否请求过开屏（只由 XiluSplashActivity.loadAd() 置位）。
     * 预加载与展示都要求它为 true —— 只请求 banner/信息流时不允许竞价开屏位、也不弹热启动开屏。
     */
    private static volatile boolean sSplashRequestedInProcess = false;

    /** 由 XiluSplashActivity 在真正发起开屏请求（loadOnly）时调用 */
    public static void markSplashRequested() {
        if (!sSplashRequestedInProcess) {
            sSplashRequestedInProcess = true;
            Log.i(TAG, "splash requested in this process -> hot-start preload/display enabled");
        }
    }

    /** 本进程是否请求过开屏 */
    public static boolean isSplashRequestedInProcess() {
        return sSplashRequestedInProcess;
    }

    /**
     * 保证热启动缓存就绪：无有效缓存时发起一次预加载竞价（展示只在缓存命中时发生）。
     * 发竞价前后要 restore()/suppress() 恢复再压回 SDK 开关，否则 SDK 的预加载会被压住。
     */
    private void ensureCacheArmed(String reason) {
        // 本进程没请求过开屏就不预加载，避免只用了 banner 也去竞价开屏位
        if (!sSplashRequestedInProcess) {
            return;
        }
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

    /** 单回合内有限次重试：竞价没出结果时再补一次，不至于一直空跑 */
    private void scheduleArm() {
        if (mArmAttempts >= MAX_ARM_ATTEMPTS) return;
        mArmAttempts++;
        mHandler.removeCallbacks(mArmTask);
        mHandler.postDelayed(mArmTask, ARM_RETRY_MS);
    }

    // ------------------------------------------------------------------
    // SDK 热启动开关 压制/恢复
    // ------------------------------------------------------------------

    /**
     * 压制 SDK 自带热启动展示：只关"SDK 自己弹"，并把预加载开关单独置回 true
     * （SDK 1.0.8.5 起预加载读独立的 isPreloadEnabled），否则开屏后的预加载会被连带关掉。
     */
    private void suppress() {
        if (mSuppressed) return;
        InitData.SplashLaunch server = serverConfig();
        if (server == null) return;    // 配置还没到，SDK 开关本来就是关的
        if (server.getHotStart() == 0) {
            mSuppressed = true;        // 服务端本来就关着，等价于已压制
            return;
        }
        HotStartSplashManager.getInstance().applySplashLaunch(copyOf(server, 0));
        // 展示压掉了，但预加载要保持开启（服务端 hotStart=1 说明要热启动广告）
        HotStartSplashManager.getInstance().setPreloadEnabled(true);
        mSuppressed = true;
        Log.i(TAG, "suppress SDK hot-start display (hotStart=0, preload 仍开启)");
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

    /**
     * 取服务端下发的 splashLaunch 配置本身（可能为 null）。
     * 这里不关心 hotStart 是 0 还是 1，只用来区分"配置还没到"和"服务端就是关的"。
     */
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
        if (isSelfOrSubclassOf(activity, SHELL_ACTIVITY)) return true;
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

    /** 是否是热启动宿主页面（业务页面）；非宿主不挂热启动开屏 */
    private static boolean isHost(Activity activity) {
        if (activity == null) {
            return false;
        }
        synchronized (sExtraHosts) {
            if (sExtraHosts.contains(activity.getClass().getName())) {
                return true;
            }
        }
        return isSelfOrSubclassOf(activity, HOST_ACTIVITY);
    }

    /** activity 的类是否就是（或继承自）指定类名。 */
    private static boolean isSelfOrSubclassOf(Activity activity, String className) {
        if (activity == null || className == null) {
            return false;
        }
        for (Class<?> c = activity.getClass(); c != null; c = c.getSuperclass()) {
            if (className.equals(c.getName())) {
                return true;
            }
        }
        return false;
    }
}
