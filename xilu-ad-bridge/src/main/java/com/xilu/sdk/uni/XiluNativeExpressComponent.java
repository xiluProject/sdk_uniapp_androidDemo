package com.xilu.sdk.uni;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.xilu.sdk.ad.ADXiluNativeAd;
import com.xilu.sdk.ad.data.ADXiluNativeAdInfo;
import com.xilu.sdk.ad.data.ADXiluNativeExpressAdInfo;
import com.xilu.sdk.ad.entity.ADXiluAdSize;
import com.xilu.sdk.ad.entity.ADXiluExtraParams;
import com.xilu.sdk.ad.error.ADXiluError;
import com.xilu.sdk.ad.listener.ADXiluNativeAdListener;
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

/**
 * 信息流模板广告组件
 */
public class XiluNativeExpressComponent extends UniComponent<FrameLayout> {

    private ADXiluNativeAd nativeAd;
    /** posId 只生效一次（同一个 ADXiluNativeAd 只有一次 loadAd 有效） */
    private boolean started = false;

    private int count = 1;
    private String sceneId = "";
    private boolean muted = true;
    private String onlySupportPlatform;
    private int adWidthPx = 0;

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
        reportHeight(container);
    }

    /** nvue 不支持 wrap_content：渲染后把实际高度回传 JS 由页面调整容器 */
    private void reportHeight(final FrameLayout container) {
        container.post(new Runnable() {
            @Override
            public void run() {
                int height = 0;
                int width = container.getWidth();
                if (width <= 0) {
                    // 尚未布局完成：退化为屏宽，避免 measureSpec 宽度为 0 导致高度测不准
                    width = container.getContext().getResources().getDisplayMetrics().widthPixels;
                }
                for (int i = 0; i < container.getChildCount(); i++) {
                    View child = container.getChildAt(i);
                    if (child.getHeight() > 0) {
                        height += child.getHeight();
                    } else {
                        child.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                        height += child.getMeasuredHeight();
                    }
                }
                if (height <= 0) return;
                Map<String, Object> params = new HashMap<>();
                params.put("height", height);
                fireEventWithDetail("onAdRender", params);
            }
        });
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
    /**
     * 触发组件事件，并把业务数据放进 {@code detail}。
     *
     * 【必须这样做】uniapp 对原生组件事件的参数有硬性约定：只有放在 {@code detail} 键下的数据
     * 才会送达 JS，其余键会被清理掉。若直接 {@code fireEvent(type, params)}，
     * JS 侧收到的 {@code e.detail} 会是空对象 {@code {}}（参数被静默丢弃）。
     * 参见 DCloud 问答：https://ask.dcloud.net.cn/question/191809
     * 「目前uni限制 参数需要放入到"detail"中 否则会被清理」。
     */
    private void fireEventWithDetail(String type, Map<String, Object> data) {
        Map<String, Object> params = new HashMap<>();
        params.put("detail", data);
        fireEvent(type, params);
    }

    @Override
    public void onActivityDestroy() {
        releaseAd();
    }
}
