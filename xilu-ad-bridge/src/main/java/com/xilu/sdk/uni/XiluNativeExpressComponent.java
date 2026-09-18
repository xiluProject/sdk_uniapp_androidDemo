package com.xilu.sdk.uni;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.xilu.sdk.ad.ADXiluNativeAd;
import com.xilu.sdk.ad.data.ADXiluNativeAdInfo;
import com.xilu.sdk.ad.data.ADXiluNativeExpressAdInfo;
import com.xilu.sdk.ad.entity.ADXiluAdSize;
import com.xilu.sdk.ad.entity.ADXiluExtraParams;
import com.xilu.sdk.ad.error.ADXiluError;
import com.xilu.sdk.ad.listener.ADXiluNativeAdListener;
import com.xilu.sdk.ad.listener.ADXiluAdSizeListener;
import com.xilu.sdk.util.ADXiluAdUtil;
import com.xilu.sdk.util.ADXiluViewUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.dcloud.feature.uniapp.UniSDKInstance;
import io.dcloud.feature.uniapp.ui.action.AbsComponentData;
import io.dcloud.feature.uniapp.ui.component.AbsVContainer;
import io.dcloud.feature.uniapp.ui.component.UniComponent;
import io.dcloud.feature.uniapp.ui.component.UniComponentProp;

// 信息流模板广告组件
public class XiluNativeExpressComponent extends UniComponent<FrameLayout> {

    private ADXiluNativeAd nativeAd;
    /** posId 只生效一次（同一个 ADXiluNativeAd 只有一次 loadAd 有效） */
    private boolean started = false;

    private int count = 1;
    private String sceneId = "";
    private boolean muted = true;
    private String onlySupportPlatform;
    private int adWidthPx = 0;

    private static final String TAG = "XiluNativeExpress";
    /** 已上报过的宽高（dp），避免同一个值反复触发 JS 调整 */
    private int reportedWidth;
    private int reportedHeight;
    /** 收敛轮询状态：WebView 模板是异步加载的，读数稳定（连续两次相同）才停 */
    private int probeTick;
    private int stableCount;
    private int lastRawH;
    private static final int MAX_PROBE_TICKS = 15;
    private static final long PROBE_TICK_MS = 400L;
    /** 尺寸轮询任务（字段持有：匿名类里要自引用继续 postDelayed） */
    private Runnable sizeTick;
    /** 平台回传的尺寸（dp），只作下限兜底：GDT/KS/MS 报的是被槽位压过的视图高度，不权威 */
    private int platformWidth;
    private int platformHeight;
    /** 平台尺寸晚于本地轮询结束时，允许重启轮询的次数上限 */
    private int platformRestarts;

    /** 取 WebView DOM 里非画布级元素的最大底边作为内容高度（CSS px 即 dp） */
    private static final String DOM_PROBE_JS =
            "(function(){" +
            "var W=window.innerWidth,H=window.innerHeight,best=0;" +
            "var a=document.body?document.body.getElementsByTagName('*'):[];" +
            "for(var i=0;i<a.length&&i<600;i++){" +
            "var r=a[i].getBoundingClientRect();" +
            "if(r.width<1||r.height<1)continue;" +
            "if(r.width>=W*0.95&&r.height>=H*0.95)continue;" + // 画布级包装层，跳过
            "if(r.bottom>best)best=r.bottom;}" +
            "return Math.round(best)+'|'+Math.round(H);})()";

    public XiluNativeExpressComponent(UniSDKInstance instance, AbsVContainer parent, int type, AbsComponentData data) {
        super(instance, parent, type, data);
    }

    public XiluNativeExpressComponent(UniSDKInstance instance, AbsVContainer parent, AbsComponentData data) {
        super(instance, parent, data);
    }

    @Override
    protected FrameLayout initComponentHostView(@NonNull Context context) {
        // 容器不得拦截点击、触摸等事件
        return new FrameLayout(context);
    }

    @UniComponentProp(name = "count")
    public void setCount(int count) {
        if (count > 0) {
            // SDK 限定取值范围内（注释声明 [1,3]）
            this.count = Math.min(count, 3);
        }
    }

    @UniComponentProp(name = "sceneId")
    public void setSceneId(String sceneId) {
        this.sceneId = sceneId == null ? "" : sceneId;
    }

    @UniComponentProp(name = "muted")
    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    /** 仅 debug 模式生效，上线时建议不设置 */
    @UniComponentProp(name = "onlySupportPlatform")
    public void setOnlySupportPlatform(String platform) {
        this.onlySupportPlatform = platform;
    }

    @UniComponentProp(name = "adWidthPx")
    public void setAdWidthPx(int widthPx) {
        this.adWidthPx = widthPx;
    }

    @UniComponentProp(name = "posId")
    public void setPosId(String posId) {
        if (started || posId == null || posId.isEmpty()) return;
        started = true;
        final String id = posId;
        // 组件属性可能分多次下发，延后到本轮属性设置结束后再加载，保证 sceneId/muted 等已就位
        getHostView().post(new Runnable() {
            @Override
            public void run() {
                startLoad(id);
            }
        });
    }

    private void startLoad(String posId) {
        Activity activity = (Activity) getContext();
        if (activity == null) return;

        nativeAd = new ADXiluNativeAd(activity);
        int width = resolveWidth();
        // 宽 = 广告宽度，高 0 = 自适应
        nativeAd.setLocalExtraParams(new ADXiluExtraParams.Builder()
                .adSize(new ADXiluAdSize(width, 0))
                .nativeAdPlayWithMute(muted)
                .build());
        // 平台渲染完成后回传真实尺寸（未挂该回调的渠道自动退回本地探测）
        nativeAd.setAdSizeListener(new ADXiluAdSizeListener() {
            @Override
            public void onAdSize(int widthPx, int heightPx) {
                onPlatformSize(widthPx, heightPx);
            }
        });
        nativeAd.setOnlySupportPlatform(onlySupportPlatform);
        // 场景 id 非必填（Demo 单条页未设置、列表页设置为空串）
        nativeAd.setSceneId(sceneId);
        nativeAd.setListener(new ADXiluNativeAdListener() {
            @Override
            public void onAdReceive(List<ADXiluNativeAdInfo> adInfoList) {
                Map<String, Object> params = new HashMap<>();
                params.put("count", adInfoList == null ? 0 : adInfoList.size());
                // 渠道/竞价信息透传（platform/ecpm）
                if (adInfoList != null && !adInfoList.isEmpty() && adInfoList.get(0) != null) {
                    ADXiluNativeAdInfo first = adInfoList.get(0);
                    params.put("platform", first.getPlatform());
                    params.put("ecpm", first.getECPM());
                    params.put("ecpmPrecision", first.getEcpmPrecision());
                    params.put("materialId", first.getMaterialId());
                    params.put("platformPosId", first.getPlatformPosId());
                }
                fireEventWithDetail("onAdReceive", params);
                // 新素材要重新收敛尺寸（WebView 内容会重新加载）
                probeTick = 0;
                stableCount = 0;
                lastRawH = 0;
                reportedWidth = 0;
                reportedHeight = 0;
                platformWidth = 0;
                platformHeight = 0;
                platformRestarts = 0;
                renderList(adInfoList);
            }

            @Override
            public void onRenderFailed(ADXiluNativeAdInfo adInfo, ADXiluError error) {
                Map<String, Object> params = new HashMap<>();
                params.put("error", error == null ? "render failed" : error.toString());
                fireEventWithDetail("onRenderFailed", params);
            }

            @Override
            public void onAdExpose(ADXiluNativeAdInfo adInfo) {
                fireEvent("onAdExpose");
            }

            @Override
            public void onAdClick(ADXiluNativeAdInfo adInfo) {
                fireEvent("onAdClick");
            }

            @Override
            public void onAdClose(ADXiluNativeAdInfo adInfo) {
                // Demo：单条页清空容器 + 释放广告实例；这里交给 JS 决定隐藏（v-if）
                fireEvent("onAdClose");
                releaseAd();
            }

            @Override
            public void onAdFailed(ADXiluError error) {
                Map<String, Object> params = new HashMap<>();
                params.put("error", error == null ? "unknown" : error.toString());
                params.put("errorText", error == null ? "unknown" : plainError(error));
                params.put("errorCode", error == null ? 0 : error.getCode());
                fireEventWithDetail("onAdFailed", params);
            }
        });
        // count 默认 1；1~3 为 SDK 声明范围
        nativeAd.loadAd(posId, count);
    }

    /** 五步渲染（每个广告一个独立子容器，render 只传该子容器） */
    private void renderList(List<ADXiluNativeAdInfo> adInfoList) {
        if (adInfoList == null || adInfoList.isEmpty()) return;
        FrameLayout container = getHostView();
        container.removeAllViews();
        for (ADXiluNativeAdInfo adInfo : adInfoList) {
            if (adInfo == null || ADXiluAdUtil.adInfoIsRelease(adInfo)) continue;
            // 同一广告位可能返回非模板广告：uni 组件不支持自渲染，直接 release 丢弃
            if (!adInfo.isNativeExpress()) {
                adInfo.release();
                continue;
            }
            ADXiluNativeExpressAdInfo express = (ADXiluNativeExpressAdInfo) adInfo;
            FrameLayout itemBox = new FrameLayout(container.getContext());
            int itemWidth = resolveWidth();
            itemBox.measure(View.MeasureSpec.makeMeasureSpec(itemWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            // 给出确定宽度再交给 SDK：容器尚未布局时宽度为 0 可能导致广告视图尺寸算错
            container.addView(itemBox, new FrameLayout.LayoutParams(
                    itemWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
            View adView = express.getNativeExpressAdView(itemBox);
            if (adView == null) {
                adInfo.release();
                continue;
            }
            // 注意：容器是 FrameLayout，LayoutParams 必须用 FrameLayout.LayoutParams
            ADXiluViewUtil.addAdViewToAdContainer(itemBox, adView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            // 必须最后调用，且参数是容器
            express.render(itemBox);
            // 信息流视频回调（可选，仅部分渠道回调），透传给 JS
            if (adInfo.isVideo()) {
                adInfo.setVideoListener(new XiluNativeVideoBridge() {
                    @Override
                    protected void emit(String event) {
                        fireEvent(event);
                    }
                });
            }
        }
        reportSize(container);
    }

    // 渲染后周期复测真实高度回传 JS：优先 DOM 内容底边，取不到再退化为原生测量求和；
    // 原生测量是物理像素，回传前要 ÷density（页面把 height 当 dp 用）
    private void reportSize(final FrameLayout container) {
        // 字段持有轮询任务：匿名类里要自引用继续 postDelayed
        sizeTick = new Runnable() {
            @Override
            public void run() {
                if (container.getChildCount() == 0) return;
                int width = container.getWidth();
                if (width <= 0) {
                    width = container.getContext().getResources().getDisplayMetrics().widthPixels;
                }
                final int wPx = width;
                float d = container.getResources().getDisplayMetrics().density;
                final float density = d > 0 ? d : 1f;
                int nativeH = 0;
                for (int i = 0; i < container.getChildCount(); i++) {
                    View child = container.getChildAt(i);
                    // 一律按"宽度确定、高度不限"重新量一次：子视图已经被槽位压扁时
                    // getHeight() 只会返回槽位高度（越量越小），量不出素材真实高度。
                    child.measure(View.MeasureSpec.makeMeasureSpec(wPx, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    nativeH += Math.max(child.getHeight(), child.getMeasuredHeight());
                }
                final int fallbackH = nativeH;
                final WebView wv = findWebView(container, 0);
                if (wv != null) {
                    wv.evaluateJavascript(DOM_PROBE_JS, new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            int domBottom = -1;
                            if (value != null) {
                                String[] p = value.replace("\"", "").split("\\|");
                                if (p.length == 2) {
                                    try { domBottom = Integer.parseInt(p[0].trim()); }
                                    catch (NumberFormatException ignored) { }
                                }
                            }
                            settleSize(container, sizeTick, wPx, domBottom, fallbackH, density);
                        }
                    });
                    return;
                }
                settleSize(container, sizeTick, wPx, -1, fallbackH, density);
            }
        };
        container.post(sizeTick);
    }

    // 平台尺寸只作下限兜底，不覆盖本地量到的内容高度：本地轮询往往更准，而平台尺寸常在
    // 轮询结束后才回来，一覆盖就把广告截断；最终高度取 max(本地内容高度, 平台尺寸)
    private void onPlatformSize(int widthPx, int heightPx) {
        FrameLayout host = getHostView();
        if (host == null || widthPx <= 0 || heightPx <= 0) return;
        float density = host.getResources().getDisplayMetrics().density;
        if (density <= 0) density = 1f;
        int w = Math.round(widthPx / density);
        int h = Math.round(heightPx / density);
        Log.d(TAG, "platform size: " + widthPx + "x" + heightPx + "px -> " + w + "x" + h + "dp"
                + ", reported=" + reportedWidth + "x" + reportedHeight + "dp"
                + ", lastDom=" + Math.round(lastRawH / density) + "dp");
        platformWidth = w;
        platformHeight = h;
        if (reportedHeight <= 0) {
            reportedWidth = w;
            reportedHeight = h;
            fireSize(w, h);
        }
        // 平台尺寸可能是本地轮询停下来之后才回来的：重启一次轮询，让本地内容高度有机会纠正
        if (sizeTick != null && platformRestarts < 2) {
            platformRestarts++;
            probeTick = 0;
            stableCount = 0;
            host.removeCallbacks(sizeTick);
            host.postDelayed(sizeTick, PROBE_TICK_MS);
        }
    }

    /** 回传尺寸给 JS（主线程安全） */
    private void fireSize(int w, int h) {
        Map<String, Object> params = new HashMap<>();
        params.put("width", w);
        params.put("height", h);
        fireEventWithDetail("onAdRender", params);
    }

    private void settleSize(FrameLayout container, Runnable tick, int wPx, int domBottom, int nativeH, float density) {
        // 高度优先用 WebView 的 DOM 内容底边（模板创意在 HTML 里），拿不到再退回原生测量
        int hPx = domBottom > 0 ? Math.round(domBottom * density) : nativeH;
        if (wPx <= 0 || hPx <= 0) {
            // 两个来源都没有有效值：等下一拍
            if (probeTick < MAX_PROBE_TICKS) {
                probeTick++;
                container.postDelayed(sizeTick, PROBE_TICK_MS);
            }
            return;
        }
        Log.d(TAG, "size probe: tick=" + probeTick + ", domBottom=" + domBottom + "dp, nativeH=" + nativeH
                + "px -> " + Math.round(wPx / density) + "x" + Math.round(hPx / density) + "dp");
        if (hPx == lastRawH) stableCount++; else { lastRawH = hPx; stableCount = 0; }
        probeTick++;
        // 连续两次读数一致就不再轮询；否则继续（WebView 还在加载时读数会变）
        if (stableCount < 2 && probeTick < MAX_PROBE_TICKS) {
            container.postDelayed(sizeTick, PROBE_TICK_MS);
        }
        int w = Math.round(wPx / density);
        int h = Math.round(hPx / density);
        // 平台尺寸只当下限：本地量到的内容更高就以本地为准（理由见 onPlatformSize 注释）
        if (h < platformHeight) h = platformHeight;
        if (w < platformWidth) w = platformWidth;
        if (w == reportedWidth && h == reportedHeight) return;
        reportedWidth = w;
        reportedHeight = h;
        Map<String, Object> params = new HashMap<>();
        params.put("width", w);
        params.put("height", h);
        fireEventWithDetail("onAdRender", params);
    }

    /** 在广告视图树里找 WebView（模板创意渲染在它里面） */
    private static WebView findWebView(View v, int depth) {
        if (v == null || depth > 12) return null;
        if (v instanceof WebView) return (WebView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                WebView found = findWebView(g.getChildAt(i), depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private int resolveWidth() {
        if (adWidthPx > 0) return adWidthPx;
        int w = getHostView().getWidth();
        if (w > 0) return w;
        return getContext().getResources().getDisplayMetrics().widthPixels;
    }

    private void releaseAd() {
        if (nativeAd != null) {
            nativeAd.release();
            nativeAd = null;
        }
    }

    /** 不含花括号的纯文本错误描述，供页面直接展示 */
    private static String plainError(ADXiluError error) {
        String msg = error.getError();
        if (msg == null || msg.isEmpty()) msg = "广告加载失败";
        return msg + "（code=" + error.getCode() + "）";
    }
    /** 触发组件事件：业务数据必须放 detail 键下，否则 JS 收到空对象；且必须回主线程 */
    private void fireEventWithDetail(final String type, Map<String, Object> data) {
        final Map<String, Object> params = new HashMap<>();
        params.put("detail", data);
        // 必须回主线程：weex 的 fireEvent 会校验线程，非主线程直接抛 WXRuntimeException 导致崩溃；
        // SDK 的失败/竞价回调是在自己的线程池上抛出来的
        if (Looper.myLooper() != Looper.getMainLooper()) {
            FrameLayout host = getHostView();
            if (host != null) {
                host.post(new Runnable() {
                    @Override
                    public void run() {
                        fireEvent(type, params);
                    }
                });
            }
            return;
        }
        fireEvent(type, params);
    }

    @Override
    public void onActivityDestroy() {
        releaseAd();
    }
}
