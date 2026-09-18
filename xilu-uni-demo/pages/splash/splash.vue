<template>
    <view class="page">
        <button class="btn" @click="show">展示开屏广告</button>
    </view>
</template>

<script>
import { POS, SPLASH, showSplash, handleAdFailed } from '@/utils/xilu-ad'

export default {
    data() {
        return {
            // 全部取自 utils/xilu-ad.js 的 SPLASH
            splashType: SPLASH.DEFAULT_TYPE,
            logoHeightPx: SPLASH.LOGO_HEIGHT_PX
        }
    },
    methods: {
        show() {
            showSplash({
                posId: POS.splash,
                splashType: this.splashType,
                // 仅半屏(2)时生效
                logoHeightPx: this.logoHeightPx,
                // 以下三项与 SplashAdActivity 的三个 setter 一一对应
                loadType: SPLASH.LOAD_AND_SHOW,
                customSkipView: SPLASH.CUSTOM_SKIP_VIEW,
                skipViewTimeMs: SPLASH.SKIP_VIEW_TIME_MS,
                onlySupportPlatform: SPLASH.ONLY_SUPPORT_PLATFORM,
                adShakeDisable: SPLASH.AD_SHAKE_DISABLE
            }, res => {
                const ev = res.event
                if (ev === 'onADTick') {
                    console.log('开屏倒计时:', res.data.millisUntilFinished)
                } else if (ev === 'onAdClose' || ev === 'onAdFailed') {
                    // 关闭/失败后返回（对应 Demo 的 jumpMain）
                    if (ev === 'onAdFailed') {
                        // 失败提示由 utils/xilu-ad.js 的 AD_TOAST.ERROR 统一控制
                        handleAdFailed('splash', res)
                    }
                    this.back()
                }
            })
        },
        back() {
            setTimeout(() => uni.navigateBack(), 300)
        }
    }
}
</script>

<style>
.page {
    padding: 24rpx;
}

.btn {
    margin-bottom: 24rpx;
}
</style>
