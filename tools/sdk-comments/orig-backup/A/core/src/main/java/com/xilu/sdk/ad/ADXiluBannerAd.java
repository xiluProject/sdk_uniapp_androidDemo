package com.xilu.sdk.ad;

import android.app.Activity;
import androidx.fragment.app.Fragment;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;

import com.xilu.sdk.ad.data.ADXiluAdType;
import com.xilu.sdk.ad.entity.ADXiluExtraParams;
import com.xilu.sdk.ad.error.ADXiluError;
import com.xilu.sdk.ad.expose.ADXiluExposeChecker;
import com.xilu.sdk.ad.expose.ADXiluExposeListener;
import com.xilu.sdk.ad.listener.ADXiluAdapterSizeListener;
import com.xilu.sdk.ad.listener.ADXiluBannerAdListener;
import com.xilu.sdk.ad.listener.ADXiluAdSizeListener;
import com.xilu.sdk.ad.scene.SceneAd;
import com.xilu.sdk.ad.utils.ADXiluLogUtil;
import com.xilu.sdk.ad.widget.ADXiluInterceptContainer;
import com.xilu.sdk.config.ADXiluErrorConfig;
import com.xilu.sdk.core.base.BaseXiluAd;
import com.xilu.sdk.util.ADXiluAdUtil;

/**
 * Created by zhangqinglou on 2025/4/18.
 */
public class ADXiluBannerAd extends BaseXiluAd<ADXiluBannerAdListener> implements SceneAd, ADXiluAdapterSizeListener {
    private RelativeLayout mContainer;
    private boolean mIsExpose;
    private ADXiluExposeChecker mExposeChecker;
    private String mSceneId;
    private ADXiluExtraParams mExtraParams;
    private int mRequestCount;

    /**
     * 平台侧实际使用的横幅尺寸（px，取请求时的框：后台配置值或屏宽×340dp 兜底，不是量渲染视图）
     */
    private ADXiluAdSizeListener mAdSizeListener;

    /** 带尺寸回调的加载重载（uni 桥接层用），回传的是请求框尺寸（px） */
    public void loadAd(final String posId, ADXiluAdSizeListener listener) {
        this.mAdSizeListener = listener;
        loadAd(posId, 1);
    }

    /** 供装载链回传横幅实际使用的尺寸（px）；<=0 视为无效，不回调 */
    @Override
    public void onAdSize(int widthPx, int heightPx) {
        // 装载链（BaseAdLoadLooper 已把本对象注册为接收方）把适配器算出的"请求时的框"送到这里，
        // 再转给桥接层注册的 ADXiluAdSizeListener：桥接层只用它当槽位高度的上限。
        ADXiluAdSizeListener l = this.mAdSizeListener;
        if (l != null && widthPx > 0 && heightPx > 0) {
            l.onAdSize(widthPx, heightPx);
        }
    }

    /**
     * @deprecated 语义与 {@link #onAdSize(int, int)} 相同；保留只是为了兼容早前按
     * "notifyAdSize" 命名接入的调用方，新代码请直接用 {@link #onAdSize(int, int)}。
     */
    @Deprecated
    public void notifyAdSize(int widthPx, int heightPx) {
        onAdSize(widthPx, heightPx);
    }

    public ADXiluBannerAd(@NonNull Activity activity, @NonNull ViewGroup viewGroup) {
        super(activity);
        addView(viewGroup);
    }

    public ADXiluBannerAd(@NonNull Fragment fragment, @NonNull ViewGroup viewGroup) {
        super(fragment);
        addView(viewGroup);
    }

    private void addView(ViewGroup viewGroup) {
        if (viewGroup != null) {
            this.mContainer = new ADXiluInterceptContainer(viewGroup.getContext());
            viewGroup.addView(this.mContainer, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        setTimeout(10000L);
    }

    @Override
    public String getAdType() {
        return ADXiluAdType.TYPE_BANNER;
    }

    @Override
    public void loadAd(final String posId, int count) {
        if (getContainer() == null) {
            if (ADXiluAdUtil.canCallBack(this)) {
                getListener().onAdFailed(new ADXiluError(ADXiluErrorConfig.AD_FAILED_CONTAINER_IS_EMPTY, ADXiluErrorConfig.MSG_AD_FAILED_CONTAINER_IS_EMPTY));
            }
        } else {
            releaseExposeCheckerr();
            this.mExposeChecker = new ADXiluExposeChecker(false, false, () -> {
                if (!mIsExpose) {
                    mIsExpose = true;
                    refreshAd(posId);
                } else {
                    ADXiluLogUtil.d("每个XiluBannerAd对象只能调用一次loadAd...");
                }
            });
            this.mExposeChecker.setShowLog(false);
            this.mExposeChecker.startExposeCheck(getContainer());
            getContainer().setMinimumHeight(50);
        }
    }

    private void releaseExposeCheckerr() {
        if (this.mExposeChecker != null) {
            this.mExposeChecker.releaseExposeCheck();
            this.mExposeChecker = null;
        }
    }

    public ADXiluExtraParams getLocalExtraParams() {
        return this.mExtraParams;
    }

    public void setLocalExtraParams(ADXiluExtraParams extraParams) {
        this.mExtraParams = extraParams;
    }

    public void refreshAd(String posId) {
        addRequestCount();
        super.loadAd(posId, 1);
    }

    @Override
    public void release() {
        if (this.mContainer != null) {
            this.mContainer.removeAllViews();
            this.mContainer = null;
        }
        releaseExposeCheckerr();
        super.release();
    }

    public RelativeLayout getContainer() {
        return this.mContainer;
    }

    @Deprecated
    public long getAutoRefreshInterval() {
        return 0L;
    }

    @Deprecated
    public void setAutoRefreshInterval(long j) {
    }

    @Override
    public String getSceneId() {
        return this.mSceneId;
    }

    @Override
    public void setSceneId(String str) {
        this.mSceneId = str;
    }

    public boolean firstRequest() {
        return this.mRequestCount == 1;
    }

    public void addRequestCount() {
        this.mRequestCount++;
    }
}
