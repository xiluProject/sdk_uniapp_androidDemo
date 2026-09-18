package com.xilu.sdk.uni;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.xilu.sdk.ad.ADXiluSplashAd;
import com.xilu.sdk.ad.data.ADXiluAdInfo;
import com.xilu.sdk.ad.entity.ADXiluAdSize;
import com.xilu.sdk.ad.entity.ADXiluExtraParams;
import com.xilu.sdk.ad.error.ADXiluError;
import com.xilu.sdk.ad.listener.ADXiluSplashAdListener;

import org.json.JSONObject;

/**
 * 开屏广告页：整页放插件内，"拉起 → 加载 → 展示"一条龙。
 * 加载方式 loadType：0 = LOAD_AND_SHOW（收到即展示，默认）、1 = LOAD_ONLY（底部按钮手动加载/展示）
 * 自定义跳过按钮 customSkipView：setSkipView(tvSkip, skipViewTimeMs)
 * 零 uni 依赖：事件经 EventCallback 转发（data 为 org.json 字符串）。
 * 容器保证不拦截点击触摸、高度 ≥ 屏高 75%、处于可见状态。
 *
 */
public class XiluSplashActivity extends Activity {

    private static final String TAG = "XiluSplash";

    /** 加载并展示 */
    public static final int LOAD_AND_SHOW = 0;
    /** 仅加载 */
    public static final int LOAD_ONLY = 1;

    /** 事件桥：dataJson 为 org.json 字符串（无数据时为 null） */
    public interface EventCallback {
        void onEvent(String event, String dataJson);
    }

    static EventCallback callback;

    public static void setCallback(EventCallback cb) {
        callback = cb;
    }

    private ADXiluSplashAd splashAd;
    private FrameLayout root;
    private FrameLayout flContainer;
    private TextView tvSkip;
    private Button btnLoad;
    private Button btnShow;

    private String posId;
    /** 0沉浸全屏 1全屏 2半屏 */
    private int splashType;
    private int logoHeightPx;
    private int loadType = LOAD_AND_SHOW;
    private boolean customSkipView = false;
    private long skipViewTimeMs = 5000;
    private boolean adLoaded = false;
    /** applyFullScreen() 幂等标记，见该方法注释 */
    private boolean immersiveApplied = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        posId = getIntent().getStringExtra("posId");
        splashType = getIntent().getIntExtra("splashType", 0);
        logoHeightPx = getIntent().getIntExtra("logoHeightPx", 0);
        loadType = getIntent().getIntExtra("loadType", LOAD_AND_SHOW);
        customSkipView = getIntent().getBooleanExtra("customSkipView", false);
        skipViewTimeMs = getIntent().getLongExtra("skipViewTimeMs", 5000);
        Log.i(TAG, "onCreate splashType=" + splashType + " (0=沉浸全屏 1=全屏 2=半屏) sdk="
                + Build.VERSION.SDK_INT);
        // 注意：这里不要设沉浸式。onCreate 阶段 decor 还没 attach，此时设置的标志/隐藏请求
        // 会被随后的 insets 分发清掉；真正生效的一次放在布局完成之后（applyFullScreenWhenReady）。
        initViews();
        applyFullScreenWhenReady();
        initAd();
    }

    /**
     * 沉浸全屏模式（splashType == 0）才隐藏系统栏。
     * 注意：splashType != 0 时不会隐藏系统栏，状态栏位置就会露出底色——
     * 如果设备上看到"状态栏还在"，先确认这一行日志里的 splashType 是否为 0。
     */
    private void applyFullScreen() {
        if (splashType != 0) {
            Log.w(TAG, "splashType=" + splashType + " != 0，跳过沉浸式（系统栏不会被隐藏）");
            // 非沉浸样式（全屏/半屏）：不隐藏系统栏，内容按系统栏内缩
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                getWindow().setDecorFitsSystemWindows(true);
            }
            return;
        }
        // 幂等保护：applyFullScreen() 会多次调用（布局后 / 400ms 兜底 / 获焦），
        // setStatusBarColor/setNavigationBarColor/setAttributes 每次都请求一次窗口 relayout，
        // 若在布局回调里无保护地调用会形成"设置→布局→再设置"的死循环。
        if (immersiveApplied) return;
        immersiveApplied = true;
        Window window = getWindow();
        // 先清掉可能残留的“老模型”全屏 flag，避免与 setDecorFitsSystemWindows 打架
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // 关键：必须先加 FLAG_LAYOUT_NO_LIMITS，否则 setDecorFitsSystemWindows(false) 首次无法铺满
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            hideSystemBars(window);
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        WindowManager.LayoutParams attributes = window.getAttributes();
        if (Build.VERSION.SDK_INT >= 28) {
            attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        window.setAttributes(attributes);
    }

    /**
     * 沉浸式自检日志（排查白条用），过滤：adb logcat -s XiluSplash
     * insetTop/insetBottom 是窗口内容区被系统栏挤掉的像素：
     * 两者都为 0 说明系统栏已隐藏、内容真正铺满整屏；
     * 若 insetTop ≈ 状态栏高 / insetBottom ≈ 导航栏高，说明窗口仍按系统栏内缩，沉浸式没生效。
     */
    private void logFullScreenState(String tag) {
        View decor = getWindow().getDecorView();
        String insets = "null";
        WindowInsets wi = decor.getRootWindowInsets();
        if (wi != null) {
            insets = wi.getSystemWindowInsetTop() + "/" + wi.getSystemWindowInsetBottom();
        }
        Log.i(TAG, tag + " decorH=" + decor.getHeight()
                + " rootH=" + (root == null ? -1 : root.getHeight())
                + " screenH=" + getResources().getDisplayMetrics().heightPixels
                + " insetTB=" + insets);
    }

    /** API 30+：隐藏状态栏 + 导航栏，滑动临时唤出 */
    private void hideSystemBars(Window window) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        try {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } catch (Throwable t) {
            Log.w(TAG, "hideSystemBars failed", t);
        }
    }


    private void applyFullScreenWhenReady() {
        if (splashType != 0) return;
        root.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (root != null) {
                            root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        }
                        applyFullScreen();
                        logFullScreenState("afterFirstLayout");
                    }
                });
        // 广告视图是异步 attach 的，部分 ROM 会在其后重发一次 insets 把系统栏"请回来"，再兜一次
        root.postDelayed(new Runnable() {
            @Override
            public void run() {
                applyFullScreen();
            }
        }, 400L);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && splashType == 0 && !isImmersiveActive()) {
            immersiveApplied = false;
            applyFullScreen();
        }
    }

    /** 检查沉浸式是否仍然生效，避免无意义的重复设置 */
    private boolean isImmersiveActive() {
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsets wi = decor.getRootWindowInsets();
            if (wi == null) return false;
            // insetTop == 0 说明状态栏没有占据空间，沉浸式仍在生效
            return wi.getSystemWindowInsetTop() == 0;
        } else {
            int flags = decor.getSystemUiVisibility();
            return (flags & View.SYSTEM_UI_FLAG_FULLSCREEN) != 0
                    && (flags & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) != 0;
        }
    }

    private void initViews() {
        // 根布局/底色见 res/layout/xilu_splash_activity.xml（match_parent + @color/xilu_splash_bg）
        root = (FrameLayout) getLayoutInflater().inflate(R.layout.xilu_splash_activity, null);
        flContainer = root.findViewById(R.id.fl_splash_container);

        LinearLayout column = root.findViewById(R.id.ll_splash_column);
        if (splashType == 2 && logoHeightPx > 0) {
            View logo = new View(this);
            logo.setBackgroundColor(Color.WHITE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, logoHeightPx);
            lp.gravity = Gravity.BOTTOM;
            column.addView(logo, lp);
        }
        // LOAD_ONLY：底部加载/展示按钮，由用户手动触发
        if (loadType == LOAD_ONLY) {
            LinearLayout bar = new LinearLayout(this);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            btnLoad = new Button(this);
            btnLoad.setText("加载广告");
            btnLoad.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    loadAd();
                }
            });
            btnShow = new Button(this);
            btnShow.setText("加载成功请展示广告");
            btnShow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showAd();
                }
            });
            bar.addView(btnLoad, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            bar.addView(btnShow, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            column.addView(bar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // 自定义跳过按钮（setSkipView(tvSkip, skipViewTimeMs)）
        if (customSkipView) {
            tvSkip = new TextView(this);
            tvSkip.setText("跳过");
            tvSkip.setTextColor(Color.WHITE);
            tvSkip.setBackgroundColor(0x80000000);
            tvSkip.setPadding(dp(12), dp(6), dp(12), dp(6));
            tvSkip.setAlpha(0f);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.topMargin = dp(30);
            lp.rightMargin = dp(20);
            root.addView(tvSkip, lp);
        }
        setContentView(root);
    }

    private void initAd() {
        // 创建开屏广告实例，第一个参数可以是 Activity 或 Fragment
        splashAd = new ADXiluSplashAd(this);

        int widthPixels = getResources().getDisplayMetrics().widthPixels;
        int heightPixels = getResources().getDisplayMetrics().heightPixels;

        // 设置整个广告视图预期宽高（目前仅穿山甲平台需要），单位为 px
        splashAd.setLocalExtraParams(new ADXiluExtraParams.Builder()
                .adSize(new ADXiluAdSize(widthPixels, heightPixels - logoHeightPx))
                .setAdShakeDisable(getIntent().getBooleanExtra("adShakeDisable", false))
                .build());
        // 沉浸式：跳过按钮距离顶部的高度会加上状态栏高度
        splashAd.setImmersive(splashType == 0);
        // 仅 debug 模式生效，上线时建议不设置
        splashAd.setOnlySupportPlatform(getIntent().getStringExtra("onlySupportPlatform"));
        // 自定义跳过按钮：倒计时默认 5 秒，SDK 范围 3000~5000，建议不修改
        if (customSkipView && tvSkip != null) {
            splashAd.setSkipView(tvSkip, skipViewTimeMs);
        }
        splashAd.setListener(new ADXiluSplashAdListener() {

            @Override
            public void onADTick(long millisUntilFinished) {
                if (customSkipView && tvSkip != null) {
                    tvSkip.setText((millisUntilFinished / 1000) + "s自动跳转");
                }
                emit("onADTick", json("millisUntilFinished", millisUntilFinished));
                // 倒计时结束自动关闭（finish 前事件已发出，JS 依据 ms<=0 跳首页）
                if (millisUntilFinished <= 0) finish();
            }

            @Override
            public void onReward(ADXiluAdInfo adInfo) {
                // 目前仅仅优量汇渠道会被使用
                emit("onReward", null);
            }

            @Override
            public void onAdSkip(ADXiluAdInfo adInfo) {
                // 不一定准确，埋点数据仅供参考
                emit("onAdSkip", null);
            }

            @Override
            public void onAdReceive(ADXiluAdInfo adInfo) {
                adLoaded = true;
                if (btnShow != null) btnShow.setText("展示广告");
                emit("onAdReceive", adInfoJson(adInfo));
                // 同一个 ADXiluSplashAd 只有一次 loadAd 有效；LOAD_AND_SHOW 时收到即展示
                if (loadType == LOAD_AND_SHOW) {
                    showAd();
                }
            }

            @Override
            public void onAdExpose(ADXiluAdInfo adInfo) {
                if (customSkipView && tvSkip != null) {
                    tvSkip.setAlpha(1f);
                }
                emit("onAdExpose", null);
            }

            @Override
            public void onAdClick(ADXiluAdInfo adInfo) {
                emit("onAdClick", null);
            }

            @Override
            public void onAdClose(ADXiluAdInfo adInfo) {
                // JS 收到后跳首页
                emit("onAdClose", null);
                getWindow().getDecorView().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!isFinishing() && !isDestroyed()) {
                            finish();
                        }
                    }
                }, 300);
            }

            @Override
            public void onAdFailed(ADXiluError error) {
                emit("onAdFailed", json("error", error == null ? "unknown" : error.toString()));
                getWindow().getDecorView().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!isFinishing() && !isDestroyed()) {
                            finish();
                        }
                    }
                }, 300);
            }
        });
        // 仅加载开屏广告，参数为广告位 ID，请在 onAdReceive 回调中展示广告
        if (loadType == LOAD_AND_SHOW) {
            loadAd();
        }
    }

    /** 加载广告（LOAD_ONLY 模式下由按钮触发） */
    private void loadAd() {
        if (splashAd == null || adLoaded) return;
        if (btnLoad != null) btnLoad.setText("加载中...");
        // 只有真正请求开屏，才允许热启动预加载/展示（避免只请求 banner 也去竞价开屏位）
        XiluHotStartRouter.markSplashRequested();
        splashAd.loadOnly(posId);
    }

    /** 展示广告（LOAD_ONLY 模式下由按钮触发） */
    private void showAd() {
        if (splashAd != null && flContainer != null) {
            splashAd.showSplash(flContainer);
            // 广告渲染后系统栏可能被 SDK/ROM 重新唤出，再兜底一次
            applyFullScreen();
        }
    }

    private void emit(String event, JSONObject data) {
        if (callback == null) return;
        callback.onEvent(event, data == null ? null : data.toString());
    }

    private static JSONObject json(String k, Object v) {
        try {
            JSONObject o = new JSONObject();
            o.put(k, v);
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /** 广告信息透传（platform/ecpm 等） */
    private static JSONObject adInfoJson(ADXiluAdInfo info) {
        try {
            JSONObject o = new JSONObject();
            if (info == null) return o;
            o.put("platform", info.getPlatform());
            o.put("ecpm", info.getECPM());
            o.put("ecpmPrecision", info.getEcpmPrecision());
            o.put("materialId", info.getMaterialId());
            o.put("platformPosId", info.getPlatformPosId());
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        // 静态回调随 finish 置空；广告实例一并释放
        callback = null;
        if (splashAd != null) {
            splashAd.release();
            splashAd = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // 禁返回（增加开屏曝光率；JS 侧依赖 onAdClose/onAdFailed/onADTick(ms<=0) 事件跳转）
    }
}
