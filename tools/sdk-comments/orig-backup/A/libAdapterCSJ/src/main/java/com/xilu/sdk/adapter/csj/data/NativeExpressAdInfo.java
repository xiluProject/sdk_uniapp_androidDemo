package com.xilu.sdk.adapter.csj.data;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;

import com.bytedance.sdk.openadsdk.TTNativeExpressAd;
import com.xilu.sdk.ad.adapter.ADXiluAdapterSizeReporter;
import com.xilu.sdk.ad.data.ADXiluNativeExpressAdInfo;
import com.xilu.sdk.ad.error.ADXiluError;
import com.xilu.sdk.ad.listener.ADXiluNativeAdListener;
import com.xilu.sdk.ad.listener.ADXiluNativeVideoListener;
import com.xilu.sdk.ad.widget.ADXiluInterceptContainer;
import com.xilu.sdk.util.ADXiluViewUtil;

/**
 * Created by zhangqinglou on 2025/4/21.
 */
public abstract class NativeExpressAdInfo extends TTNativeExpressAdInfo<ADXiluNativeAdListener> implements ADXiluNativeExpressAdInfo, TTNativeExpressAd.ExpressAdInteractionListener, TTNativeExpressAd.ExpressVideoAdListener {
    private int width;
    private int height;
    private ADXiluNativeVideoListener videoListener;
    private boolean isExposeReported = false;
    private ADXiluInterceptContainer container;
    private Handler handler;
    private ViewTreeObserver.OnGlobalLayoutListener mLayoutListener;
    /** 平台渲染尺寸转发器：装载链通过它拿到渲染后的真实宽高（为 null 表示链路未接通） */
    private ADXiluAdapterSizeReporter sizeReporter;

    public NativeExpressAdInfo(int width, int height, Activity activity, String platformPosId) {
        this(width, height, activity, platformPosId, null);
    }

    public NativeExpressAdInfo(int width, int height, Activity activity, String platformPosId, ADXiluAdapterSizeReporter sizeReporter) {
        super(activity, platformPosId);
        this.handler = new Handler(Looper.getMainLooper());
        this.width = width;
        this.height = height;
        this.sizeReporter = sizeReporter;
    }

    private void c() {
        if (getAdapterAdInfo() != null) {
            getAdapterAdInfo().setExpressInteractionListener(this);
            getAdapterAdInfo().render();
        }
    }

    public boolean isNativeExpress() {
        return true;
    }

    public boolean isVideo() {
        return getAdapterAdInfo() != null && 5 == getAdapterAdInfo().getImageMode();
    }

    public void setVideoListener(ADXiluNativeVideoListener videoListener) {
        if (isVideo()) {
            this.videoListener = videoListener;
            getAdapterAdInfo().setVideoAdListener(this);
        }
    }

    public View getNativeExpressAdView(@NonNull ViewGroup viewGroup) {
        View expressAdView;
        ADXiluViewUtil.releaseClickTouchListener(viewGroup, new View[0]);
        if (this.container == null && getAdapterAdInfo() != null && (expressAdView = ((TTNativeExpressAd) getAdapterAdInfo()).getExpressAdView()) != null) {
            // 修复：如果expressAdView没有LayoutParams，手动设置合适的LayoutParams
            if (expressAdView.getLayoutParams() == null) {
                expressAdView.setLayoutParams(new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            this.container = new ADXiluInterceptContainer(expressAdView.getContext());
            ADXiluInterceptContainer interceptContainer = this.container;
            int w = this.width;
            if (this.width <= 0) {
                w = ViewGroup.LayoutParams.MATCH_PARENT;
            }
            int h = this.height;
            if (this.height <= 0) {
                h = ViewGroup.LayoutParams.WRAP_CONTENT;
            }
            interceptContainer.setLayoutParams(new ViewGroup.LayoutParams(w, h));
            this.container.addResponseClickView(expressAdView);
        }
        return this.container;
    }

    public void render(@NonNull ViewGroup viewGroup) {
        getNativeExpressAdView(viewGroup);
    }

    /** 回传穿山甲模板渲染后的真实尺寸（px）；量不到就不上报 */
    protected void reportRenderSize(View view) {
        if (this.sizeReporter == null || view == null) {
            return;
        }
        if (reportSizeIfMeasured(view)) {
            return;
        }
        final View target = view;
        if (this.handler != null) {
            this.handler.post(new Runnable() {
                @Override
                public void run() {
                    reportSizeIfMeasured(target);
                }
            });
        }
    }

    /** View 已量出有效宽高就上报（px）；否则返回 false */
    private boolean reportSizeIfMeasured(View view) {
        ADXiluAdapterSizeReporter reporter = this.sizeReporter;
        if (reporter == null || view == null) {
            return false;
        }
        int widthPx = view.getWidth() > 0 ? view.getWidth() : view.getMeasuredWidth();
        int heightPx = view.getHeight() > 0 ? view.getHeight() : view.getMeasuredHeight();
        return reporter.report(widthPx, heightPx);
    }

    public void onVideoLoad() {
        Handler handler = this.handler;
        if (handler != null) {
            handler.post(() -> {
                if (videoListener != null) {
                    videoListener.onVideoLoad(NativeExpressAdInfo.this);
                }
            });
        }
    }

    public void onVideoError(int i, int i2) {
        Handler handler = this.handler;
        if (handler != null) {
            handler.post(() -> {
                if (videoListener != null) {
                    videoListener.onVideoError(NativeExpressAdInfo.this, new ADXiluError(i, "extraCode : " + i2));
                }
            });
        }
    }

    public void onVideoAdStartPlay() {
        if (this.handler != null) {
            this.handler.post(() -> {
                if (videoListener != null) {
                    videoListener.onVideoStart(NativeExpressAdInfo.this);
                }
            });
        }
    }

    public void onVideoAdPaused() {
        if (this.handler != null) {
            this.handler.post(() -> {
                if (videoListener != null) {
                    videoListener.onVideoPause(NativeExpressAdInfo.this);
                }
            });
        }
    }

    public void onVideoAdContinuePlay() {
    }

    public void onProgressUpdate(long j, long j2) {
    }

    public void onVideoAdComplete() {
        if (this.handler != null) {
            this.handler.post(() -> {
                if (videoListener != null) {
                    videoListener.onVideoComplete(NativeExpressAdInfo.this);
                }
            });
        }
    }

    public void onClickRetry() {
    }

    @Override
    public void onCloseClick(View view) {
        if (this.handler != null) {
            this.handler.post(new Runnable() {
                @Override
                public void run() {
                    if (getAdListener() != null) {
                        getAdListener().onAdClose(NativeExpressAdInfo.this);
                    }
                }
            });
        }
    }

    @Override
    public void releaseAdapter() {
        super.releaseAdapter();
        this.videoListener = null;
        this.sizeReporter = null;
        Handler handler = this.handler;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
            this.handler = null;
        }
        // 清理ViewTreeObserver监听器，防止内存泄漏
        if (this.container != null && mLayoutListener != null) {
            ViewTreeObserver vto = this.container.getViewTreeObserver();
            if (vto != null && vto.isAlive()) {
                vto.removeOnGlobalLayoutListener(mLayoutListener);
            }
            ADXiluViewUtil.removeSelfFromParent(new View[]{this.container});
            this.container = null;
        }
        mLayoutListener = null;
    }

    public void onAdClicked(View view, int i) {
        Handler handler = this.handler;
        if (handler != null) {
            handler.post(() -> {
                if (getAdListener() != null) {
                    getAdListener().onAdClick(NativeExpressAdInfo.this);
                }
            });
        }
    }

    public void onAdShow(View view, int i) {
        Handler handler = this.handler;
        if (handler != null) {
            handler.post(() -> {
                if (getAdapterAdInfo() != null && getAdapterAdInfo().getMediationManager() != null && getAdapterAdInfo().getMediationManager().getShowEcpm() != null) {
                    try {
                        String ecpmStr = getAdapterAdInfo().getMediationManager().getShowEcpm().getEcpm();
                        if (ecpmStr != null && !ecpmStr.isEmpty()) {
                            double ecpm = Double.parseDouble(ecpmStr);
                            if (ecpm < 0) {
                                ecpm = 0;
                            }
                            setBidECPMCent((int) ecpm);
                        }
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
                // 使用ViewTreeObserver监听View的实际显示状态
                if (container == null || isExposeReported) {
                    return;
                }

                // 先立即检查View是否已经可见
                if (container.getWidth() > 0 && container.getHeight() > 0 && container.isShown()) {
                    isExposeReported = true;
                    if (getAdListener() != null) {
                        getAdListener().onAdExpose(NativeExpressAdInfo.this);
                    }
                    return;
                }

                // 如果还没显示，添加监听器等待
                mLayoutListener = new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        // 如果已经上报，移除监听器并返回
                        if (isExposeReported) {
                            ViewTreeObserver vto = container.getViewTreeObserver();
                            if (vto != null && vto.isAlive()) {
                                vto.removeOnGlobalLayoutListener(this);
                            }
                            return;
                        }
                        if (container.getWidth() > 0 && container.getHeight() > 0 && container.isShown()) {
                            isExposeReported = true;
                            ViewTreeObserver vto = container.getViewTreeObserver();
                            if (vto != null && vto.isAlive()) {
                                vto.removeOnGlobalLayoutListener(this);
                            }
                            if (getAdListener() != null) {
                                getAdListener().onAdExpose(NativeExpressAdInfo.this);
                            }
                        }
                    }
                };
                container.getViewTreeObserver().addOnGlobalLayoutListener(mLayoutListener);
            });
        }
    }

    @Override
    public void setAdapterAdInfo(TTNativeExpressAd tTNativeExpressAd) {
        super.setAdapterAdInfo(tTNativeExpressAd);
        c();
    }
}
