package com.xilu.sdk.uni;

import android.app.Activity;
import android.content.Context;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.xilu.sdk.ad.ADXiluBannerAd;
import com.xilu.sdk.ad.data.ADXiluAdInfo;
import com.xilu.sdk.ad.entity.ADXiluExtraParams;
import com.xilu.sdk.ad.error.ADXiluError;
import com.xilu.sdk.ad.listener.ADXiluBannerAdListener;

import java.util.HashMap;
import java.util.Map;

import io.dcloud.feature.uniapp.UniSDKInstance;
import io.dcloud.feature.uniapp.ui.action.AbsComponentData;
import io.dcloud.feature.uniapp.ui.component.AbsVContainer;
import io.dcloud.feature.uniapp.ui.component.UniComponent;
import io.dcloud.feature.uniapp.ui.component.UniComponentProp;

/**
 * Banner 广告组件（必须用于 nvue 页面并显式宽高，容器不得拦截触摸事件）
 * 属性：posId、sceneId、onlySupportPlatform、refreshInterval（自刷新间隔，秒，30~120）；
 *      事件：onAdReceive/onAdExpose/onAdClick/onAdClose/onAdFailed
 */
public class XiluBannerComponent extends UniComponent<FrameLayout> {

    /** 自刷新间隔默认值，BANNER_AD_AUTO_REFRESH_INTERVAL = 30 */
    private static final int DEFAULT_REFRESH_INTERVAL = 30;
    /** SDK 限定范围：30~120 秒 */
    private static final int MIN_REFRESH_INTERVAL = 30;
    private static final int MAX_REFRESH_INTERVAL = 120;

    private ADXiluBannerAd bannerAd;
    private String sceneId = "";
    private String onlySupportPlatform;
    private int refreshInterval = DEFAULT_REFRESH_INTERVAL;
    private boolean adShakeDisable = false;

    /** posId 只生效一次 */
    private boolean started;

    public XiluBannerComponent(UniSDKInstance instance, AbsVContainer parent, int type, AbsComponentData data) {
        super(instance, parent, type, data);
    }

    public XiluBannerComponent(UniSDKInstance instance, AbsVContainer parent, AbsComponentData data) {
        super(instance, parent, data);
    }

    @Override
    protected FrameLayout initComponentHostView(@NonNull Context context) {
        // 容器不得拦截点击、触摸等事件
        return new FrameLayout(context);
    }

    @UniComponentProp(name = "sceneId")
    public void setSceneId(String sceneId) {
        this.sceneId = sceneId == null ? "" : sceneId;
        if (bannerAd != null) bannerAd.setSceneId(this.sceneId);
    }

    /** 仅 debug 模式生效，上线时建议不设置 */
    @UniComponentProp(name = "onlySupportPlatform")
    public void setOnlySupportPlatform(String platform) {
        this.onlySupportPlatform = platform;
        if (bannerAd != null) bannerAd.setOnlySupportPlatform(platform);
    }

    /** 自刷新间隔（秒），超出 30~120 会被夹到边界；不传则用 DEFAULT_REFRESH_INTERVAL */
    @UniComponentProp(name = "refreshInterval")
    public void setRefreshInterval(int interval) {
        int v = interval;
        if (v < MIN_REFRESH_INTERVAL) v = MIN_REFRESH_INTERVAL;
        if (v > MAX_REFRESH_INTERVAL) v = MAX_REFRESH_INTERVAL;
        this.refreshInterval = v;
        if (bannerAd != null) bannerAd.setAutoRefreshInterval(v);
    }

    /** 控制摇一摇是否禁用 */
    @UniComponentProp(name = "adShakeDisable")
    public void setAdShakeDisable(boolean disable) {
        this.adShakeDisable = disable;
    }

    @UniComponentProp(name = "posId")
    public void setPosId(String posId) {
        if (started || posId == null || posId.isEmpty()) return;
        started = true;
        final String id = posId;
        // 属性可能分多批下发，延后到本轮属性设置结束后再创建/加载，保证 sceneId/refreshInterval 等已就位
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
        // 第一个参数可以是 Activity 或 Fragment，第二个参数是广告容器
        bannerAd = new ADXiluBannerAd(activity, getHostView());
        // 自刷新时间范围 30~120 秒（初始化 SDK 须传入 Application context）
        bannerAd.setAutoRefreshInterval(refreshInterval);
        bannerAd.setLocalExtraParams(new ADXiluExtraParams.Builder()
                .setAdShakeDisable(adShakeDisable)
                .build());
        bannerAd.setOnlySupportPlatform(onlySupportPlatform);
        bannerAd.setSceneId(sceneId);
        bannerAd.setListener(new ADXiluBannerAdListener() {
            @Override
            public void onAdReceive(ADXiluAdInfo adInfo) {
                Map<String, Object> params = new HashMap<>();
                if (adInfo != null) {
                    params.put("platform", adInfo.getPlatform());
                    params.put("ecpm", adInfo.getECPM());
                    params.put("ecpmPrecision", adInfo.getEcpmPrecision());
                    params.put("materialId", adInfo.getMaterialId());
                    params.put("platformPosId", adInfo.getPlatformPosId());
                }
                fireEventWithDetail("onAdReceive", params);
            }

            @Override
            public void onAdExpose(ADXiluAdInfo adInfo) {
                fireEvent("onAdExpose");
            }

            @Override
            public void onAdClick(ADXiluAdInfo adInfo) {
                fireEvent("onAdClick");
            }

            @Override
            public void onAdClose(ADXiluAdInfo adInfo) {
                fireEvent("onAdClose");
                // Demo 的 onAdClose 释放两个对象：bannerAd + adInfo
                if (adInfo != null) {
                    adInfo.release();
                }
                release();
            }

            @Override
            public void onAdFailed(ADXiluError error) {
                // 与 Demo 差异：不 finish 页面，由 JS 决定隐藏容器（v-if）
                Map<String, Object> params = new HashMap<>();
                params.put("error", error == null ? "unknown" : error.toString());
                params.put("errorText", error == null ? "unknown" : plainError(error));
                params.put("errorCode", error == null ? 0 : error.getCode());
                fireEventWithDetail("onAdFailed", params);
                // 失败后立即释放：否则这个 bannerAd 仍被 SDK 持有并继续自刷新/重试，
                // 页面又把容器隐藏了，等于留了一个后台持续渲染的活广告对象。
                release();
            }
        });
        bannerAd.loadAd(posId);
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

    private void release() {
        if (bannerAd != null) {
            bannerAd.release();
            bannerAd = null;
        }
        // 允许 release 后重新传入 posId 重建（页面通常用 v-if 重建组件实例）
        started = false;
    }

    @Override
    public void onActivityDestroy() {
        release();
    }

    /**
     * 【关键】组件销毁钩子必须实现。
     *
     * <p>nvue 页面里"重新加载"是 {@code v-if} 重建组件（banner.nvue 的 reload()），
     * 组件被移除时 **不会** 触发 {@code onActivityDestroy()}（Activity 还活着），
     * 于是旧的 {@code ADXiluBannerAd} 永远不会被 release —— 而 SDK 的 banner 自刷新
     * （30~120 秒、默认 30，SDK 侧强制）仍在跑，结果是同一个页面上叠着多个
     * 仍在刷新渲染的活广告对象（WebView/EGL 线程不断创建销毁）。
     *
     * <p>页面 onUnload 时同理：不实现 destroy() 就等于把广告留在后台继续渲染。
     */
    @Override
    public void destroy() {
        release();
        super.destroy();
    }
}
