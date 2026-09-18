package com.xilu.sdk.adapter.bqt.data;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;

import com.baidu.mobads.sdk.api.ExpressResponse;
import com.xilu.sdk.ad.adapter.ADXiluAdapterSizeReporter;
import com.xilu.sdk.ad.data.ADXiluNativeExpressAdInfo;
import com.xilu.sdk.ad.error.ADXiluError;
import com.xilu.sdk.ad.listener.ADXiluNativeAdListener;
import com.xilu.sdk.ad.listener.ADXiluNativeVideoListener;
import com.xilu.sdk.ad.listener.ADXiluSingleClickListener;
import com.xilu.sdk.adapter.bqt.R;
import com.xilu.sdk.util.ADXiluDisplayUtil;
import com.xilu.sdk.util.ADXiluViewUtil;

/**
 * Created by zhangqinglou on 2025/4/28.
 */
public class NativeExpressAdInfo extends BaseAdInfo<ADXiluNativeAdListener, ExpressResponse> implements ADXiluNativeExpressAdInfo, ExpressResponse.ExpressInteractionListener, ExpressResponse.ExpressDislikeListener {
    private boolean isMute;
    private RelativeLayout mLayout;
    private View mAdView;
    private Handler mHandler;
    private boolean isExposeReported = false;
    private ViewTreeObserver.OnGlobalLayoutListener mLayoutListener;
    /** 平台渲染尺寸转发器（装载链未接通时为 null） */
    private final ADXiluAdapterSizeReporter mSizeReporter;
    private boolean isSizeReported = false;

    public NativeExpressAdInfo(String platformPosId, boolean isMute) {
        this(platformPosId, isMute, null);
    }

    public NativeExpressAdInfo(String platformPosId, boolean isMute, ADXiluAdapterSizeReporter sizeReporter) {
        super(platformPosId);
        this.mHandler = new Handler(Looper.getMainLooper());
        this.isMute = isMute;
        this.mSizeReporter = sizeReporter;
    }

    public View getNativeExpressAdView(@NonNull ViewGroup viewGroup) {
        if (this.mLayout == null) {
            this.mLayout = new RelativeLayout(viewGroup.getContext());
            if (this.mAdView == null && getAdapterAdInfo() != null) {
                getAdapterAdInfo().setInteractionListener(this);
                getAdapterAdInfo().setAdDislikeListener(this);
                this.mAdView = getAdapterAdInfo().getExpressAdView();
            }
            // 修复：如果mAdView没有LayoutParams，手动设置合适的LayoutParams
            if (this.mAdView != null && this.mAdView.getLayoutParams() == null) {
                this.mAdView.setLayoutParams(new RelativeLayout.LayoutParams(
                        RelativeLayout.LayoutParams.MATCH_PARENT,
                        RelativeLayout.LayoutParams.WRAP_CONTENT));
            }
            this.mLayout.addView(this.mAdView);
            ImageView imageView = new ImageView(viewGroup.getContext());
            RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(ADXiluDisplayUtil.dp2px(20), ADXiluDisplayUtil.dp2px(20));
            layoutParams.addRule(10);
            layoutParams.addRule(11);
            layoutParams.topMargin = ADXiluDisplayUtil.dp2px(10);
            layoutParams.rightMargin = ADXiluDisplayUtil.dp2px(10);
            imageView.setImageResource(R.drawable.adxilu_baidu_round_icon_close);
            this.mLayout.addView(imageView, layoutParams);
            registerCloseView(imageView);
            imageView.setOnClickListener(new ADXiluSingleClickListener() {
                @Override
                public void onSingleClick(View view) {
                    if (mHandler != null) {
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (getAdListener() != null) {
                                    getAdListener().onAdClose(NativeExpressAdInfo.this);
                                }
                            }
                        });
                    }
                }
            });
        }
        return this.mLayout;
    }

    public void render(@NonNull ViewGroup viewGroup) {
        if (this.mAdView == null || getAdapterAdInfo() == null) {
            return;
        }
        getAdapterAdInfo().render();
    }

    public boolean isNativeExpress() {
        return true;
    }

    public boolean isVideo() {
        return false;
    }

    public void setVideoListener(ADXiluNativeVideoListener videoListener) {
    }

    public void onAdClick() {
        if (this.mHandler != null) {
            this.mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (getAdListener() != null) {
                        getAdListener().onAdClick(NativeExpressAdInfo.this);
                    }
                }
            });
        }
    }

        public void onAdExposed() {
            if (this.mHandler != null) {
                this.mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mLayout == null || isExposeReported) {
                            return;
                        }

                        // 先立即检查View是否已经可见
                        if (mLayout.getWidth() > 0 && mLayout.getHeight() > 0 && mLayout.isShown()) {
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
                                    ViewTreeObserver vto = mLayout.getViewTreeObserver();
                                    if (vto != null && vto.isAlive()) {
                                        vto.removeOnGlobalLayoutListener(this);
                                    }
                                    return;
                                }
                                if (mLayout.getWidth() > 0 && mLayout.getHeight() > 0 && mLayout.isShown()) {
                                    isExposeReported = true;
                                    ViewTreeObserver vto = mLayout.getViewTreeObserver();
                                    if (vto != null && vto.isAlive()) {
                                        vto.removeOnGlobalLayoutListener(this);
                                    }
                                    if (getAdListener() != null) {
                                        getAdListener().onAdExpose(NativeExpressAdInfo.this);
                                    }
                                }
                            }
                        };
                        mLayout.getViewTreeObserver().addOnGlobalLayoutListener(mLayoutListener);
                    }
                });
            }
        }

        public void onAdRenderFail(View view, String str, int i) {
            if (this.mHandler != null) {
            this.mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (getAdListener() != null) {
                        getAdListener().onRenderFailed(NativeExpressAdInfo.this, new ADXiluError(i, str));
                    }
                }
            });
        }
    }

    public void onAdRenderSuccess(View view, float f, float f2) {
        reportRenderSize(view);
    }

    /** 上报百青藤模板渲染完成后的真实尺寸（px）；量不到不上报 */
    private void reportRenderSize(View view) {
        if (this.isSizeReported || this.mSizeReporter == null) {
            return;
        }
        int widthPx = 0;
        int heightPx = 0;
        if (view != null) {
            widthPx = view.getWidth() > 0 ? view.getWidth() : view.getMeasuredWidth();
            heightPx = view.getHeight() > 0 ? view.getHeight() : view.getMeasuredHeight();
        }
        if (this.mSizeReporter.report(widthPx, heightPx)) {
            this.isSizeReported = true;
        }
    }

    public void onAdUnionClick() {
        if (this.mHandler != null) {
            this.mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (getAdListener() != null) {
                        getAdListener().onAdClick(NativeExpressAdInfo.this);
                    }
                }
            });
        }
    }

    @Override
    public void onCloseClick(View view) {
        if (this.mHandler != null) {
            this.mHandler.post(new Runnable() {
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
        // 清理ViewTreeObserver监听器，防止内存泄漏
        if (this.mLayout != null && mLayoutListener != null) {
            ViewTreeObserver vto = this.mLayout.getViewTreeObserver();
            if (vto != null && vto.isAlive()) {
                vto.removeOnGlobalLayoutListener(mLayoutListener);
            }
        }
        mLayoutListener = null;
        if (this.mLayout != null) {
            ADXiluViewUtil.removeSelfFromParent(new View[]{this.mLayout});
            this.mLayout = null;
        }
        if (this.mAdView != null) {
            ADXiluViewUtil.removeSelfFromParent(new View[]{this.mAdView});
            this.mAdView = null;
        }
        if (this.mHandler != null) {
            this.mHandler.removeCallbacksAndMessages(null);
            this.mHandler = null;
        }
    }

    public void onDislikeWindowShow() {
    }

    public void onDislikeItemClick(String str) {
        if (this.mHandler != null) {
            this.mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (getAdListener() != null) {
                        getAdListener().onAdClose(NativeExpressAdInfo.this);
                    }
                }
            });
        }
    }

    public void onDislikeWindowClose() {
        if (this.mHandler != null) {
            this.mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (getAdListener() != null) {
                        getAdListener().onAdClose(NativeExpressAdInfo.this);
                    }
                }
            });
        }
    }
}
