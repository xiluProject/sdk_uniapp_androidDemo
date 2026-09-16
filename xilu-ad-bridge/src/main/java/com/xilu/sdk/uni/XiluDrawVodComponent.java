package com.xilu.sdk.uni;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.xilu.sdk.ad.ADXiluDrawVodAd;
import com.xilu.sdk.ad.data.ADXiluDrawVodAdInfo;
import com.xilu.sdk.ad.entity.ADXiluAdSize;
import com.xilu.sdk.ad.entity.ADXiluExtraParams;
import com.xilu.sdk.ad.error.ADXiluError;
import com.xilu.sdk.ad.listener.ADXiluDrawVodAdListener;
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
 * Draw 视频信息流组件
 */
public class XiluDrawVodComponent extends UniComponent<FrameLayout> {

    private ADXiluDrawVodAd drawVodAd;
    private ADXiluDrawVodAdInfo adInfo;

    /** posId 只生效一次（同一个 ADXiluDrawVodAd 只有一次 loadAd 有效） */
    private boolean started = false;

    private int count = 1;
    private String onlySupportPlatform;
    private int adWidthPx = 0;
    private int adHeightPx = 0;

    public XiluDrawVodComponent(UniSDKInstance instance, AbsVContainer parent, int type, AbsComponentData data) {
        super(instance, parent, type, data);
    }

    public XiluDrawVodComponent(UniSDKInstance instance, AbsVContainer parent, AbsComponentData data) {
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
            this.count = Math.min(count, 3);
        }
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

    @UniComponentProp(name = "adHeightPx")
    public void setAdHeightPx(int heightPx) {
        this.adHeightPx = heightPx;
    }

    @UniComponentProp(name = "posId")
    public void setPosId(String posId) {
        if (started || posId == null || posId.isEmpty()) return;
        started = true;
        final String id = posId;
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

        drawVodAd = new ADXiluDrawVodAd(activity);
        // Draw 的宽高都必须 > 0
        drawVodAd.setLocalExtraParams(new ADXiluExtraParams.Builder()
                .adSize(new ADXiluAdSize(resolveWidth(), resolveHeight()))
                .build());
        drawVodAd.setOnlySupportPlatform(onlySupportPlatform);
        drawVodAd.setListener(new ADXiluDrawVodAdListener() {
            @Override
            public void onAdReceive(List<ADXiluDrawVodAdInfo> adInfoList) {
                Map<String, Object> params = new HashMap<>();
                params.put("count", adInfoList == null ? 0 : adInfoList.size());
                // 渠道/竞价信息透传（platform/ecpm）
                if (adInfoList != null && !adInfoList.isEmpty() && adInfoList.get(0) != null) {
                    ADXiluDrawVodAdInfo first = adInfoList.get(0);
                    params.put("platform", first.getPlatform());
                    params.put("ecpm", first.getECPM());
                    params.put("ecpmPrecision", first.getEcpmPrecision());
                    params.put("materialId", first.getMaterialId());
                    params.put("platformPosId", first.getPlatformPosId());
                }
                fireEventWithDetail("onAdReceive", params);
                pickAndRender(adInfoList);
            }

            @Override
            public void onRenderFailed(ADXiluDrawVodAdInfo info, ADXiluError error) {
                Map<String, Object> params = new HashMap<>();
                params.put("error", error == null ? "render failed" : error.toString());
                fireEventWithDetail("onRenderFailed", params);
            }

            @Override
            public void onAdExpose(ADXiluDrawVodAdInfo info) {
                fireEvent("onAdExpose");
            }

            @Override
            public void onAdClick(ADXiluDrawVodAdInfo info) {
                fireEvent("onAdClick");
            }

            @Override
            public void onAdClose(ADXiluDrawVodAdInfo info) {
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
        drawVodAd.loadAd(posId, count);
    }

    /** 渲染第一条，其余 release */
    private void pickAndRender(List<ADXiluDrawVodAdInfo> adInfoList) {
        if (adInfoList == null || adInfoList.isEmpty()) return;
        ADXiluDrawVodAdInfo picked = null;
        for (ADXiluDrawVodAdInfo info : adInfoList) {
            if (info == null || ADXiluAdUtil.adInfoIsRelease(info)) continue;
            if (picked == null) {
                picked = info;
            } else {
                info.release();
            }
        }
        if (picked == null) return;
        adInfo = picked;

        FrameLayout container = getHostView();
        container.removeAllViews();
        FrameLayout box = new FrameLayout(container.getContext());
        int width = resolveWidth();
        int height = resolveHeight();
        box.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        container.addView(box, new FrameLayout.LayoutParams(width, height));

        // 视频回调（Demo 对 Draw 是无条件设置，不判 isVideo）
        picked.setVideoListener(new XiluDrawVodVideoBridge() {
            @Override
            protected void emit(String event) {
                fireEvent(event);
            }
        });
        View mediaView = picked.getMediaView(box);
        if (mediaView == null) {
            picked.release();
            adInfo = null;
            return;
        }
        // 与模板信息流不同：Draw 用 2 参重载（LayoutParams 传 null → 走 addView(view)）
        ADXiluViewUtil.addAdViewToAdContainer(box, mediaView);
        // 必须最后调用，且参数是容器
        picked.render(box);
        reportHeight(container);
    }

    /** nvue 不支持 wrap_content：渲染后把实际高度回传 JS 由页面调整容器 */
    private void reportHeight(final FrameLayout container) {
        container.post(new Runnable() {
            @Override
            public void run() {
                View child = container.getChildAt(0);
                if (child == null) return;
                int height = child.getHeight();
                if (height <= 0) {
                    height = child.getMeasuredHeight();
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

    private int resolveHeight() {
        if (adHeightPx > 0) return adHeightPx;
        int h = getHostView().getHeight();
        if (h > 0) return h;
        return getContext().getResources().getDisplayMetrics().heightPixels;
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

    private void releaseAd() {
        if (adInfo != null) {
            adInfo.release();
            adInfo = null;
        }
        if (drawVodAd != null) {
            drawVodAd.release();
            drawVodAd = null;
        }
    }

    @Override
    public void onActivityDestroy() {
        releaseAd();
    }
}
