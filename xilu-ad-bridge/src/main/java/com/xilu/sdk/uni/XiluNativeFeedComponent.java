package com.xilu.sdk.uni;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.graphics.Color;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.xilu.sdk.ADXiluSdk;
import com.xilu.sdk.ad.ADXiluNativeAd;
import com.xilu.sdk.ad.data.ADXiluNativeAdInfo;
import com.xilu.sdk.ad.data.ADXiluNativeFeedAdInfo;
import com.xilu.sdk.ad.entity.ADXiluAdSize;
import com.xilu.sdk.ad.entity.ADXiluExtraParams;
import com.xilu.sdk.ad.error.ADXiluError;
import com.xilu.sdk.ad.listener.ADXiluNativeAdListener;
import com.xilu.sdk.config.ADXiluImageLoader;
import com.xilu.sdk.util.ADXiluAdUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.dcloud.feature.uniapp.UniSDKInstance;
import io.dcloud.feature.uniapp.ui.action.AbsComponentData;
import io.dcloud.feature.uniapp.ui.component.AbsVContainer;
import io.dcloud.feature.uniapp.ui.component.UniComponent;
import io.dcloud.feature.uniapp.ui.component.UniComponentProp;

// 信息流自渲染广告组件
public class XiluNativeFeedComponent extends UniComponent<FrameLayout> {

    /** 日志过滤：adb logcat -s XiluNativeFeed */
    private static final String TAG = "XiluNativeFeed";

    private static final int DEFAULT_COUNT = 1;
    private static final int MIN_COUNT = 1;
    private static final int MAX_COUNT = 3;

    private ADXiluNativeAd nativeAd;
    private ADXiluNativeAdInfo adInfo;

    /** posId 只生效一次 */
    private boolean started = false;

    private String sceneId = "";
    private boolean muted = true;
    private int count = DEFAULT_COUNT;
    private String onlySupportPlatform;
    private int adWidthPx = 0;

    public XiluNativeFeedComponent(UniSDKInstance instance, AbsVContainer parent, int type, AbsComponentData data) {
        super(instance, parent, type, data);
    }

    public XiluNativeFeedComponent(UniSDKInstance instance, AbsVContainer parent, AbsComponentData data) {
        super(instance, parent, data);
    }

    @Override
    protected FrameLayout initComponentHostView(@NonNull Context context) {
        return new FrameLayout(context);
    }

    @UniComponentProp(name = "sceneId")
    public void setSceneId(String sceneId) {
        this.sceneId = sceneId == null ? "" : sceneId;
    }

    @UniComponentProp(name = "muted")
    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    /** 一次拉取条数，1~3，超出会被夹到边界（本组件只渲染第一条） */
    @UniComponentProp(name = "count")
    public void setCount(int count) {
        int v = count;
        if (v < MIN_COUNT) v = MIN_COUNT;
        if (v > MAX_COUNT) v = MAX_COUNT;
        this.count = v;
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
        nativeAd.setLocalExtraParams(new ADXiluExtraParams.Builder()
                .adSize(new ADXiluAdSize(resolveWidth(), 0))
                .nativeAdPlayWithMute(muted)
                .build());
        nativeAd.setOnlySupportPlatform(onlySupportPlatform);
        nativeAd.setSceneId(sceneId);
        nativeAd.setListener(new ADXiluNativeAdListener() {
            @Override
            public void onAdReceive(List<ADXiluNativeAdInfo> adInfoList) {
                Log.i(TAG, "onAdReceive size=" + (adInfoList == null ? 0 : adInfoList.size()));
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
                pickAndRender(adInfoList);
            }

            @Override
            public void onRenderFailed(ADXiluNativeAdInfo info, ADXiluError error) {
                Log.w(TAG, "onRenderFailed " + (error == null ? "render failed" : error.toString()));
                Map<String, Object> params = new HashMap<>();
                params.put("error", error == null ? "render failed" : error.toString());
                fireEventWithDetail("onRenderFailed", params);
            }

            @Override
            public void onAdExpose(ADXiluNativeAdInfo info) {
                fireEvent("onAdExpose");
            }

            @Override
            public void onAdClick(ADXiluNativeAdInfo info) {
                fireEvent("onAdClick");
            }

            @Override
            public void onAdClose(ADXiluNativeAdInfo info) {
                fireEvent("onAdClose");
                releaseAd();
            }

            @Override
            public void onAdFailed(ADXiluError error) {
                Log.w(TAG, "onAdFailed posId=" + posId + " error=" + (error == null ? "unknown" : error.toString()));
                Map<String, Object> params = new HashMap<>();
                // error 是 SDK 原始 JSON，errorText 是不含花括号的纯文本，errorCode 便于页面按码判断
                params.put("error", error == null ? "unknown" : error.toString());
                params.put("errorText", error == null ? "unknown" : plainError(error));
                params.put("errorCode", error == null ? 0 : error.getCode());
                fireEventWithDetail("onAdFailed", params);
            }
        });
        nativeAd.loadAd(posId, count);
    }

    /** 不含花括号的纯文本错误描述，供页面直接展示 */
    private static String plainError(ADXiluError error) {
        String msg = error.getError();
        if (msg == null || msg.isEmpty()) msg = "广告加载失败";
        return msg + "（code=" + error.getCode() + "）";
    }

    /** 广告类型摘要，用于说明"该广告位未返回自渲染广告"时实际返回了什么 */
    private static String describeTypes(List<ADXiluNativeAdInfo> adInfoList) {
        if (adInfoList == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < adInfoList.size(); i++) {
            if (i > 0) sb.append(',');
            ADXiluNativeAdInfo info = adInfoList.get(i);
            if (info == null) {
                sb.append("null");
            } else if (info instanceof ADXiluNativeFeedAdInfo) {
                sb.append("feed");
            } else if (info.isNativeExpress()) {
                sb.append("express");
            } else {
                sb.append(info.getClass().getSimpleName());
            }
        }
        return sb.append(']').toString();
    }

    /** 只渲染第一条自渲染广告，其余（含模板广告）一律 release，避免泄漏 */
    private void pickAndRender(List<ADXiluNativeAdInfo> adInfoList) {
        if (adInfoList == null || adInfoList.isEmpty()) return;
        ADXiluNativeAdInfo picked = null;
        for (ADXiluNativeAdInfo info : adInfoList) {
            if (info == null || ADXiluAdUtil.adInfoIsRelease(info)) continue;
            if (info.isNativeExpress() || !(info instanceof ADXiluNativeFeedAdInfo)) {
                // 模板广告：本组件不处理，释放丢弃（改用 <xilu-native-express>）
                info.release();
                continue;
            }
            if (picked == null) {
                picked = info;
            } else {
                info.release();
            }
        }
        if (picked == null) {
            // 注意：走到这里说明请求成功但返回的不是自渲染广告（或全部已释放），
            // 通常是"该广告位后台配置的是模板广告"，把 types 一起带出去便于定位
            String types = describeTypes(adInfoList);
            Log.w(TAG, "未取到自渲染广告 types=" + types);
            Map<String, Object> params = new HashMap<>();
            params.put("error", "该广告位未返回自渲染广告 types=" + types);
            fireEventWithDetail("onAdFailed", params);
            return;
        }
        adInfo = picked;
        ADXiluNativeFeedAdInfo feed = (ADXiluNativeFeedAdInfo) picked;
        if (feed.isVideo()) {
            feed.setVideoListener(new XiluNativeVideoBridge() {
                @Override
                protected void emit(String event) {
                    fireEvent(event);
                }
            });
        }
        renderCard(feed);
    }

    /** 用 View 拼自渲染卡片 */
    private void renderCard(ADXiluNativeFeedAdInfo feed) {
        FrameLayout root = getHostView();
        Context ctx = root.getContext();
        root.removeAllViews();

        int pad = dp(ctx, 12);
        // 对齐原生 Demo（NativeSelfRenderActivity#showAd + item_native_ad_native_ad.xml）：
        // 交互容器用 RelativeLayout，素材挂在自己的子容器里，最后同步调用 registerViewForInteraction。
        RelativeLayout card = new RelativeLayout(ctx);
        card.setBackgroundColor(Color.WHITE);
        card.setPadding(pad, pad, pad, dp(ctx, 8));
        root.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 图标（左上）
        ImageView icon = new ImageView(ctx);
        icon.setId(View.generateViewId());
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setBackgroundColor(Color.parseColor("#EEEEEE"));
        RelativeLayout.LayoutParams iconLp = new RelativeLayout.LayoutParams(dp(ctx, 56), dp(ctx, 56));
        iconLp.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        iconLp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        card.addView(icon, iconLp);

        // 标题（图标右侧，单行）
        TextView title = new TextView(ctx);
        title.setId(View.generateViewId());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTextColor(Color.parseColor("#333333"));
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        RelativeLayout.LayoutParams titleLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.addRule(RelativeLayout.RIGHT_OF, icon.getId());
        titleLp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        titleLp.leftMargin = dp(ctx, 8);
        card.addView(title, titleLp);

        // 描述（图标右下对齐）
        TextView desc = new TextView(ctx);
        desc.setId(View.generateViewId());
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        desc.setTextColor(Color.parseColor("#999999"));
        desc.setMaxLines(2);
        desc.setEllipsize(TextUtils.TruncateAt.END);
        RelativeLayout.LayoutParams descLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descLp.addRule(RelativeLayout.RIGHT_OF, icon.getId());
        descLp.addRule(RelativeLayout.ALIGN_BOTTOM, icon.getId());
        descLp.leftMargin = dp(ctx, 8);
        descLp.topMargin = dp(ctx, 4);
        card.addView(desc, descLp);

        // 素材区（16:9，与 Demo 的 flMediaContainer 一致），位于图标下方
        FrameLayout media = new FrameLayout(ctx);
        media.setId(View.generateViewId());
        media.setBackgroundColor(Color.parseColor("#EEEEEE"));
        RelativeLayout.LayoutParams mediaLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mediaLp.addRule(RelativeLayout.BELOW, icon.getId());
        mediaLp.topMargin = dp(ctx, 8);
        card.addView(media, mediaLp);

        // 关闭按钮（右上角）
        ImageView close = new ImageView(ctx);
        close.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        RelativeLayout.LayoutParams closeLp = new RelativeLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20));
        closeLp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        closeLp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        card.addView(close, closeLp);

        // 底部：CTA + 平台标识
        TextView cta = new TextView(ctx);
        cta.setId(View.generateViewId());
        cta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        cta.setTextColor(Color.parseColor("#007AFF"));
        cta.setGravity(Gravity.CENTER);
        cta.setBackgroundColor(Color.parseColor("#E8F1FF"));
        cta.setPadding(dp(ctx, 12), dp(ctx, 4), dp(ctx, 12), dp(ctx, 4));
        RelativeLayout.LayoutParams ctaLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ctaLp.addRule(RelativeLayout.BELOW, media.getId());
        ctaLp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        ctaLp.topMargin = dp(ctx, 4);
        card.addView(cta, ctaLp);

        ImageView adTarget = new ImageView(ctx);
        RelativeLayout.LayoutParams targetLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 14));
        targetLp.addRule(RelativeLayout.BELOW, media.getId());
        targetLp.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        targetLp.topMargin = dp(ctx, 4);
        card.addView(adTarget, targetLp);

        // 先按确定宽度测量一次：getMediaView(容器) 依赖容器已有宽度，而此刻组件尚未布局
        root.measure(View.MeasureSpec.makeMeasureSpec(resolveWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));

        // 数据绑定（title/desc/ctaText/iconUrl/platformIcon/mediaView）
        title.setText(feed.getTitle() == null ? "" : feed.getTitle());
        desc.setText(feed.getDesc() == null ? "" : feed.getDesc());
        cta.setText(feed.getCtaText() == null ? "" : feed.getCtaText());
        loadImage(icon, feed.getIconUrl());
        int platformIcon = feed.getPlatformIcon();
        if (platformIcon != 0) {
            adTarget.setImageResource(platformIcon);
        }
        if (feed.hasMediaView()) {
            // 与 Demo 一致：素材必须挂到容器里且可见，再注册交互
            View mediaView = feed.getMediaView(media);
            if (mediaView != null && mediaView.getParent() != media) {
                if (mediaView.getParent() instanceof ViewGroup) {
                    ((ViewGroup) mediaView.getParent()).removeView(mediaView);
                }
                media.addView(mediaView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            // 16:9 占位，保证素材有确定高度
            int mediaWidth = resolveWidth();
            media.getLayoutParams().height = mediaWidth * 9 / 16;
            media.requestLayout();
        } else {
            ImageView image = new ImageView(ctx);
            image.setId(View.generateViewId());
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            RelativeLayout.LayoutParams imageLp = new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, resolveWidth() * 9 / 16);
            imageLp.addRule(RelativeLayout.BELOW, icon.getId());
            imageLp.topMargin = dp(ctx, 8);
            imageLp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
            card.addView(image, imageLp);
            loadImage(image, feed.getImageUrl());
        }

        // 注册关闭按钮（交给 SDK 托管，以便回调 onAdClose）
        feed.registerCloseView(close);
        // 注册广告交互必须调用且放在最后；第一个参数 container 必须是 FrameLayout，
        // 否则 SDK 里 instanceof 判断不过就整段跳过 addView，卡片建好但素材永远挂不上（故传 media 而非 card）
        feed.registerViewForInteraction(media, card);

        reportHeight(root);
    }

    /** 图片加载：优先用宿主设置的 SDK ImageLoader，其次反射 Glide（存在则用），都没有则不显示 */
    private void loadImage(ImageView imageView, String url) {
        if (imageView == null || url == null || url.isEmpty()) return;
        try {
            ADXiluImageLoader loader = ADXiluSdk.getInstance().getImageLoader();
            if (loader != null) {
                loader.loadImage(getContext(), url, imageView);
                return;
            }
        } catch (Throwable ignore) {
        }
        try {
            Class<?> glide = Class.forName("com.bumptech.glide.Glide");
            Object requestManager = glide.getMethod("with", View.class).invoke(null, imageView);
            Object requestBuilder = requestManager.getClass().getMethod("load", String.class)
                    .invoke(requestManager, url);
            requestBuilder.getClass().getMethod("into", ImageView.class).invoke(requestBuilder, imageView);
        } catch (Throwable ignore) {
            // 宿主既未设置 ADXiluImageLoader 也没有 Glide：图标/图片留空，不影响文字与点击链路
        }
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
                    int width = container.getWidth();
                    if (width <= 0) {
                        // 尚未布局完成：退化为屏宽，避免 measureSpec 宽度为 0 导致高度测不准
                        width = container.getContext().getResources().getDisplayMetrics().widthPixels;
                    }
                    child.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    height = child.getMeasuredHeight();
                }
                if (height <= 0) return;
                // 卡片是原生控件拼的，读数为物理像素，而页面把 onAdRender 的 height 当 dp 用，
                // 必须 ÷density，否则槽位放大 density 倍、广告下方留一大片空白
                float density = container.getContext().getResources().getDisplayMetrics().density;
                if (density <= 0) density = 1f;
                Map<String, Object> params = new HashMap<>();
                params.put("height", Math.round(height / density));
                fireEventWithDetail("onAdRender", params);
            }
        });
    }

    /** 触发组件事件：业务数据必须放 detail 键下，否则 JS 收到空对象；且必须回主线程 */
    private void fireEventWithDetail(final String type, Map<String, Object> data) {
        final Map<String, Object> params = new HashMap<>();
        params.put("detail", data);
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

    private int resolveWidth() {
        if (adWidthPx > 0) return adWidthPx;
        int w = getHostView().getWidth();
        if (w > 0) return w;
        return getContext().getResources().getDisplayMetrics().widthPixels;
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void releaseAd() {
        if (adInfo != null) {
            adInfo.release();
            adInfo = null;
        }
        if (nativeAd != null) {
            nativeAd.release();
            nativeAd = null;
        }
    }

    @Override
    public void onActivityDestroy() {
        releaseAd();
    }
}
