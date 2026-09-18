package com.xilu.sdk.ad.adapter;

import com.xilu.sdk.ad.data.IBasePlatformInfo;
import com.xilu.sdk.ad.data.IBasePlatformPosInfo;
import com.xilu.sdk.ad.entity.ADXiluAdSize;

/**
 * Created by zhangqinglou on 2025/4/15.
 */
public class ADXiluAdapterParams {
    private String posId;
    private IBasePlatformPosInfo platformPosInfo;
    private IBasePlatformInfo platform;
    private boolean reward;
    private int count;
    private boolean compelRefresh;

    public ADXiluAdapterParams(IBasePlatformPosInfo platformPosInfo, IBasePlatformInfo platformInfo, boolean reward, int count, String posId) {
        this.reward = reward;
        this.platform = platformInfo;
        this.platformPosInfo = platformPosInfo;
        this.count = count;
        this.posId = posId;
    }

    public IBasePlatformInfo getPlatform() {
        return this.platform;
    }

    public String getPosId() {
        return this.posId;
    }

    public IBasePlatformPosInfo getPlatformPosInfo() {
        return this.platformPosInfo;
    }

    public int getCount() {
        return this.count;
    }

    public boolean isReward() {
        return this.reward;
    }

    public boolean isCompelRefresh() {
        return this.compelRefresh;
    }

    /** 算出横幅请求框尺寸（px）：后台配置优先，未配置用屏宽×340dp 兜底 */
    public int[] computeAdSizePx(int fallbackScreenWidthPx, int fallbackHeightPx) {
        return computeAdSizePx(null, fallbackScreenWidthPx, fallbackHeightPx);
    }

    /** 同上，但 App 指定尺寸优先于后台配置与兜底 */
    public int[] computeAdSizePx(ADXiluAdSize appAdSize, int fallbackScreenWidthPx, int fallbackHeightPx) {
        int widthPx = 0;
        int heightPx = 0;
        if (appAdSize != null) {
            widthPx = appAdSize.getWidth();
            heightPx = appAdSize.getHeight();
        }
        ADXiluAdSize adSize = this.platformPosInfo == null ? null : this.platformPosInfo.getAdSize();
        if (adSize != null) {
            if (widthPx <= 0) {
                widthPx = adSize.getWidth();
            }
            if (heightPx <= 0) {
                heightPx = adSize.getHeight();
            }
        }
        if (widthPx <= 0 && fallbackScreenWidthPx > 0) {
            widthPx = fallbackScreenWidthPx;
        }
        if (heightPx <= 0 && fallbackHeightPx > 0) {
            heightPx = fallbackHeightPx;
        }
        return new int[]{widthPx, heightPx};
    }
}
