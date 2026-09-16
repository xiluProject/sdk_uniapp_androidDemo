<template>
    <view class="page">
        <button class="btn" @click="load">{{ loading ? '加载中...' : '获取全屏视频广告' }}</button>
        <button class="btn" :disabled="!ready" @click="show">展示全屏视频广告</button>
    </view>
</template>

<script>
import { POS, FULL_SCREEN, loadFullScreenVod, showFullScreenVod, handleAdFailed } from '@/utils/xilu-ad'

export default {
    data() {
        return {
            ready: false,
            loading: false
        }
    },
    methods: {
        load() {
            this.ready = false
            this.loading = true
                // 参数全部取自 utils/xilu-ad.js 的 FULL_SCREEN
            loadFullScreenVod({
                posId: POS.fullscreen,
                onlySupportPlatform: FULL_SCREEN.ONLY_SUPPORT_PLATFORM
            }, res => {
                const ev = res.event
                if (ev === 'onAdReceive') {
                    this.ready = true
                    this.loading = false
                    uni.showToast({ title: '全屏视频广告获取成功' })
                } else if (ev === 'onAdClose') {
                    // 一次成功拉取的广告数据只允许展示一次，展示后须重新加载
                    this.ready = false
                } else if (ev === 'onAdFailed') {
                    this.loading = false
                    // 失败提示由 utils/xilu-ad.js 的 AD_TOAST.ERROR 统一控制
                    handleAdFailed('fullscreen', res)
                }
            })
        },
        show() {
            showFullScreenVod({}, res => {
                if (res.event === 'onShowFailed') {
                    // 三重校验失败：无可用广告 / 广告未准备好 / 广告已失效
                    handleAdFailed('fullscreen-show', res)
                }
            })
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
