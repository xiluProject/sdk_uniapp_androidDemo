package com.xilu.sdk.ad.data;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.DrawableRes;

import com.xilu.sdk.ad.adapter.ADXiluAdapterParams;
import com.xilu.sdk.ad.listener.ADXiluAdapterSizeListener;
import com.xilu.sdk.ad.listener.ADXiluAdListener;
import com.xilu.sdk.ad.listener.ADXiluSingleClickListener;
import com.xilu.sdk.config.ADXiluConfig;
import com.xilu.sdk.core.manager.ADSdkManager;
import com.xilu.sdk.core.util.PriceUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by zhangqinglou on 2025/4/15.
 */
public abstract class ADXiluBaseAdInfo<T extends ADXiluAdListener, E> implements ADXiluAdInfo, ADXiluAdapterSizeListener {
    private String platform;
    private String platformPosId;
    private int platformIcon;
    private boolean isRelease;
    private ADXiluSingleClickListener actionClickListener;
    private ADXiluSingleClickListener closeClickListener;
    private ADXiluSingleClickListener clickListener;
    protected T adListener;
    private E i;
    private Map<String, Object> extInfo;
    private IBasePlatformPosInfo xiluPlatformPosId;
    private double ecpm;
    private boolean ecpmSet = false;
    private boolean bidWinner;

    /** 平台渲染尺寸的接收方（装载链在 loadAd 时把广告信息对象注册进来），见 onAdSize 注释 */
    private ADXiluAdapterSizeListener adapterSizeListener;

    public ADXiluBaseAdInfo() {
    }

    public ADXiluBaseAdInfo(ADXiluAdapterParams params) {
        setAdapterParams(params);
    }

    public ADXiluBaseAdInfo(String platform, String platformPosId, @DrawableRes int platformIcon) {
        this.platform = platform;
        this.platformPosId = platformPosId;
        this.platformIcon = platformIcon;
    }

    private IBasePlatformPosInfo getXiluPlatformPosId() {
        if (this.xiluPlatformPosId == null) {
            this.xiluPlatformPosId = ADSdkManager.getInstance().getPlatformPosId(this.platformPosId);
        }
        return this.xiluPlatformPosId;
    }

    public void setAdapterParams(ADXiluAdapterParams adapterParams) {
        if (adapterParams != null) {
            if (adapterParams.getPlatform() != null) {
                this.platform = adapterParams.getPlatform().getPlatform();
            }
            this.xiluPlatformPosId = adapterParams.getPlatformPosInfo();
            if (adapterParams.getPlatformPosInfo() != null) {
                this.platformPosId = adapterParams.getPlatformPosInfo().getPlatformPosId();
            }
        }
    }

    public T getAdListener() {
        return this.adListener;
    }

    public void setAdListener(T t) {
        this.adListener = t;
    }

    public E getAdapterAdInfo() {
        return this.i;
    }

    public void setAdapterAdInfo(E e) {
        this.i = e;
    }

    @Override
    public String getPlatform() {
        return this.platform;
    }

    @Override
    public int getPlatformIcon() {
        return this.platformIcon;
    }

    public void setPlatformIcon(int i) {
        this.platformIcon = i;
    }

    @Override
    public String getPlatformPosId() {
        return this.platformPosId;
    }

    @Override
    public String getMaterialId() {
        // materialId 默认返回初始化接口返回的 adSlotId（即 platformPosId）
        return getPlatformPosId();
    }

    @Override
    public boolean isReleased() {
        return this.isRelease;
    }

    @Override
    public final void release() {
        this.isRelease = true;
        this.actionClickListener = null;
        this.closeClickListener = null;
        this.clickListener = null;
        this.adListener = null;
        try {
            releaseAdapter();
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public void registerCloseView(View view) {
        if (view != null) {
            if (this.closeClickListener == null) {
                this.closeClickListener = new ADXiluSingleClickListener() {
                    @Override
                    public void onSingleClick(View view2) {
                        ADXiluBaseAdInfo.this.onCloseClick(view2);
                    }
                };
            }
            view.setOnClickListener(this.closeClickListener);
        }
    }

    public void setAdContainerClickListener(ViewGroup viewGroup) {
        if (viewGroup != null) {
            if (this.clickListener == null) {
                this.clickListener = new ADXiluSingleClickListener() {
                    @Override
                    public void onSingleClick(View view) {
                        ADXiluBaseAdInfo.this.onAdContainerClick(view);
                    }
                };
            }
            viewGroup.setOnClickListener(this.clickListener);
        }
    }

    public void setActionClickListener(ViewGroup viewGroup, View... viewArr) {
        if (viewArr == null || viewArr.length <= 0) {
            return;
        }
        if (this.actionClickListener == null) {
            this.actionClickListener = new ADXiluSingleClickListener() {
                @Override
                public void onSingleClick(View view) {
                    ADXiluBaseAdInfo.this.onActionClick(getContainer(), view);
                }
            };
        }
        this.actionClickListener.setContainer(viewGroup);
        for (View view : viewArr) {
            if (view != null && view != viewGroup) {
                view.setOnClickListener(this.actionClickListener);
            }
        }
    }

    public ADXiluSingleClickListener getActionClickListener() {
        return this.actionClickListener;
    }

    public ADXiluSingleClickListener getCloseClickListener() {
        return this.closeClickListener;
    }

    public ADXiluSingleClickListener getClickListener() {
        return this.clickListener;
    }

    public Map<String, Object> getExtInfo() {
        if (this.extInfo == null) {
            this.extInfo = new HashMap<>();
        }
        return this.extInfo;
    }

    public abstract void releaseAdapter();

    public abstract void onCloseClick(View view);

    public abstract void onAdContainerClick(View view);

    public abstract void onActionClick(ViewGroup viewGroup, View view);

    public void setBidECPMFen(double fen) {
        this.ecpm = fen;
        this.ecpmSet = true;
    }

    public void setBidECPMCent(int cent) {
        // 统一用分，不再转元
        this.ecpm = cent;
        this.ecpmSet = true;
    }

    public double getBidECPMFen() {
        return this.ecpm;
    }

    public boolean isBidWinner() {
        return this.bidWinner;
    }

    public void setBidWinner(boolean bidWinner) {
        this.bidWinner = bidWinner;
    }

    @Override
    public double getECPM() {
        if (ADXiluConfig.EcpmType.ACCURATE.equals(getEcpmPrecision())) {
            return this.ecpm;
        }
        if (this.ecpmSet && this.ecpm > 0) {
            return this.ecpm;
        }
        return 0.0d;
    }

    @Override
    public String getEcpmPrecision() {
        return getXiluPlatformPosId() != null ? getXiluPlatformPosId().getEcpmPrecision() : ADXiluConfig.EcpmType.ERROR;
    }


    /** 装载链是否已注册接收方 */
    protected boolean hasAdapterSizeListener() {
        return this.adapterSizeListener != null;
    }

    /** 适配器把平台渲染尺寸上抛给装载链；未注册接收方时返回 false */
    protected boolean dispatchAdSize(int widthPx, int heightPx) {
        if (widthPx <= 0 || heightPx <= 0) {
            return false;
        }
        ADXiluAdapterSizeListener listener = this.adapterSizeListener;
        if (listener == null) {
            return false;
        }
        listener.onAdSize(widthPx, heightPx);
        return true;
    }

    public void setAdapterSizeListener(ADXiluAdapterSizeListener listener) {
        this.adapterSizeListener = listener;
    }

    /** 装载链回传平台渲染后素材真实宽高（px）；非正值丢弃 */
    @Override
    public void onAdSize(int widthPx, int heightPx) {
        // 本对象就是装载链注册的接收方
        dispatchAdSize(widthPx, heightPx);
    }
}